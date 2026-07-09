package com.momstarter.dev;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Fail-closed guard for {@code momstarter.dev.expose-reset-token=true}.
 *
 * <p>This bean exists ONLY when the flag is on. Its {@code @PostConstruct} enforces three
 * chained conditions (ANDed); any failure throws {@link IllegalStateException} and prevents
 * Spring from completing context refresh — the application never starts.
 *
 * <ul>
 *   <li><b>G2 (allow-list profile)</b>: active profiles MUST contain {@code local}
 *       (case-insensitive) AND MUST NOT contain any of
 *       {@code prod / production / staging / stg / preprod}.</li>
 *   <li><b>G3 (allow-list datasource)</b>: the resolved datasource host must be
 *       {@code localhost / 127.0.0.1 / ::1 / [::1]} (exact) or the URL must be an embedded
 *       H2 form ({@code jdbc:h2:mem:} / {@code jdbc:h2:file:}).
 *       Remote H2 ({@code jdbc:h2:tcp:} / {@code jdbc:h2:ssl:}) is rejected explicitly.
 *       Substring matches (e.g. {@code localhost.attacker.com}) are rejected via proper
 *       host parsing.</li>
 *   <li><b>G4 (fail-closed default)</b>: absent / blank / unparseable datasource URL → throw.</li>
 * </ul>
 *
 * <p>This guard is intentionally a SEPARATE bean from {@link DevModeGuard} so that setting
 * <em>only</em> {@code expose-reset-token=true} (without {@code auto-verify-email}) still
 * engages a guard. One {@code @ConditionalOnProperty} cannot OR two flag names.
 */
@Component
@ConditionalOnProperty(name = "momstarter.dev.expose-reset-token", havingValue = "true")
public class ResetTokenExposureGuard {

    private static final Logger log = LoggerFactory.getLogger(ResetTokenExposureGuard.class);

    /** Profiles that MUST appear for the flag to be permitted. */
    private static final Set<String> REQUIRED_PROFILES = Set.of("local");

    /** Deny-list is a second layer; no amount of local profile should allow these. */
    private static final Set<String> DENIED_PROFILES = Set.of(
            "prod", "production", "staging", "stg", "preprod");

    /** Exact-match set of allowed hosts. Anything else is rejected. */
    private static final Set<String> LOCAL_HOSTS = Set.of(
            "localhost", "127.0.0.1", "::1", "[::1]");

    /** Embedded H2 URL prefixes that bypass host parsing (no network socket). */
    private static final List<String> EMBEDDED_H2_PREFIXES = List.of(
            "jdbc:h2:mem:", "jdbc:h2:file:");

    /** Remote H2 prefixes — must be rejected explicitly even though they start with jdbc:h2:. */
    private static final List<String> REMOTE_H2_PREFIXES = List.of(
            "jdbc:h2:tcp:", "jdbc:h2:ssl:");

    private final Environment environment;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    public ResetTokenExposureGuard(Environment environment) {
        this.environment = environment;
    }

    /** Invoked by Spring after field injection. Package-visible for direct unit testing. */
    @PostConstruct
    void checkSafety() {
        String[] activeProfiles = environment.getActiveProfiles();
        checkG2Profiles(activeProfiles);
        checkG3DatasourceUrl(datasourceUrl);

        log.warn("""
                ╔══════════════════════════════════════════════════════════════════════════╗
                ║  ⚠ DEV RESET-TOKEN EXPOSURE ACTIVE — LOCAL ONLY                        ║
                ║  momstarter.dev.expose-reset-token=true                                 ║
                ║  Reset links/tokens will be logged at WARN to the local console.        ║
                ║  Active profiles : {}
                ║  Datasource URL  : {}
                ║  NEVER enable in staging or production.                                 ║
                ╚══════════════════════════════════════════════════════════════════════════╝""",
                Arrays.toString(activeProfiles), datasourceUrl);
    }

    // ── G2: profile allow-list ────────────────────────────────────────────────────

    private void checkG2Profiles(String[] activeProfiles) {
        boolean hasLocal = Arrays.stream(activeProfiles)
                .anyMatch(p -> REQUIRED_PROFILES.contains(p.toLowerCase()));

        if (!hasLocal) {
            throw fail("G2 profile check: active profiles " + Arrays.toString(activeProfiles)
                    + " do not contain 'local'. momstarter.dev.expose-reset-token=true"
                    + " is only permitted with active profile 'local'.");
        }

        boolean hasDenied = Arrays.stream(activeProfiles)
                .anyMatch(p -> DENIED_PROFILES.contains(p.toLowerCase()));

        if (hasDenied) {
            throw fail("G2 profile check: active profiles " + Arrays.toString(activeProfiles)
                    + " contain a denied profile (prod/production/staging/stg/preprod).");
        }
    }

    // ── G3 + G4: datasource allow-list with strict host parsing ──────────────────

    private void checkG3DatasourceUrl(String url) {
        // G4: blank/null URL is unknown = fail-closed
        if (url == null || url.isBlank()) {
            throw fail("G4: spring.datasource.url is absent or blank — unknown datasource"
                    + " is unsafe. Set expose-reset-token=false or configure a local datasource.");
        }

        // Embedded H2 allow-list (no host to parse)
        for (String prefix : EMBEDDED_H2_PREFIXES) {
            if (url.startsWith(prefix)) {
                return; // G3 passes: embedded H2 is local by definition
            }
        }

        // Remote H2 reject (before any host parsing)
        for (String prefix : REMOTE_H2_PREFIXES) {
            if (url.startsWith(prefix)) {
                throw fail("G3: remote H2 datasource '" + url
                        + "' is not permitted. Only embedded H2 (jdbc:h2:mem: / jdbc:h2:file:) or"
                        + " localhost-class databases are allowed with expose-reset-token=true.");
            }
        }

        // All other jdbc: URLs — parse the actual host
        String host = extractHost(url);
        if (!LOCAL_HOSTS.contains(host)) {
            throw fail("G3: datasource host '" + host + "' parsed from URL '" + url
                    + "' is not in the local-host allow-list " + LOCAL_HOSTS
                    + ". expose-reset-token=true is only permitted against localhost-class databases.");
        }
    }

    /**
     * Extracts the network host from a JDBC URL by stripping the outer {@code jdbc:} scheme
     * and parsing the inner URI. Returns the hostname (lowercase) or throws on parse ambiguity
     * (G4 fail-closed).
     *
     * <p>Handles:
     * <ul>
     *   <li>userinfo@ trap: uses {@link URI#getHost()} which correctly ignores userinfo</li>
     *   <li>query strings: URI parsing strips them</li>
     *   <li>IPv6 brackets: {@link URI#getHost()} returns {@code ::1} without brackets, but
     *       some JDBC strings have brackets in the authority; we normalise both forms.</li>
     *   <li>multi-host reject: any comma signals multi-host; we reject conservatively.</li>
     * </ul>
     */
    private static String extractHost(String jdbcUrl) {
        // Strip leading "jdbc:" so we can parse the inner part as a proper URI
        String inner = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;

        // Multi-host detection: commas in the authority segment (e.g. host1,host2)
        // We detect this conservatively before parsing
        if (containsMultiHost(inner)) {
            throw fail("G3/G4: multi-host datasource URL '" + jdbcUrl
                    + "' detected. All hosts must be local; rejecting conservatively.");
        }

        try {
            URI uri = new URI(inner);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw fail("G4: could not parse host from datasource URL '" + jdbcUrl
                        + "'. Unknown = unsafe.");
            }
            // Normalise IPv6: URI.getHost() strips brackets but some configs use [::1]
            String normalised = host.toLowerCase().replaceAll("[\\[\\]]", "");
            // Re-check bracket form for [::1]
            if ("::1".equals(normalised)) {
                return normalised; // matches LOCAL_HOSTS "::1"
            }
            return normalised;
        } catch (URISyntaxException e) {
            throw fail("G4: datasource URL '" + jdbcUrl
                    + "' could not be parsed as a URI: " + e.getReason()
                    + ". Unknown = unsafe.");
        }
    }

    /**
     * Detects multi-host JDBC URLs by checking for a comma in the authority portion.
     * This is a conservative reject — if we can't be sure, G4 rejects.
     */
    private static boolean containsMultiHost(String inner) {
        // Check only the authority segment (between // and the first /)
        int authStart = inner.indexOf("//");
        if (authStart < 0) return false;
        authStart += 2;
        int authEnd = inner.indexOf('/', authStart);
        String authority = authEnd < 0 ? inner.substring(authStart) : inner.substring(authStart, authEnd);
        // Strip userinfo (before @)
        int atIdx = authority.lastIndexOf('@');
        if (atIdx >= 0) authority = authority.substring(atIdx + 1);
        // Strip port
        int colonIdx = authority.lastIndexOf(':');
        String hostPart = colonIdx >= 0 ? authority.substring(0, colonIdx) : authority;
        // Strip query
        int qIdx = hostPart.indexOf('?');
        if (qIdx >= 0) hostPart = hostPart.substring(0, qIdx);
        return hostPart.contains(",");
    }

    private static IllegalStateException fail(String detail) {
        String msg = "momstarter.dev.expose-reset-token=true refused to start: " + detail
                + " Disable expose-reset-token or use a local datasource with active profile 'local'.";
        log.error("""
                ╔══════════════════════════════════════════════════════════════════════════╗
                ║  SECURITY ALERT — RESET TOKEN EXPOSURE FLAG IN NON-LOCAL ENVIRONMENT!  ║
                ║  momstarter.dev.expose-reset-token=true is LOCAL ONLY.                  ║
                ║  {}
                ║  Refusing to start.                                                     ║
                ╚══════════════════════════════════════════════════════════════════════════╝""", detail);
        return new IllegalStateException(msg);
    }
}
