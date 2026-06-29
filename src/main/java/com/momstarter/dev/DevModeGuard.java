package com.momstarter.dev;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Fail-safe guard that prevents momstarter.dev.auto-verify-email=true from running
 * in production or against a non-local database.
 *
 * <p>This bean is created ONLY when the dev flag is true (via @ConditionalOnProperty).
 * On @PostConstruct it inspects the active Spring profiles and the datasource URL:
 * <ul>
 *   <li>If any active profile is "prod" (case-insensitive) → throw IllegalStateException</li>
 *   <li>If the datasource URL does not reference localhost / 127.0.0.1 / ::1 / h2: → throw</li>
 * </ul>
 * This ensures that even a misconfiguration (e.g. someone copies application-local.yml to prod)
 * causes an immediate, loud startup failure rather than a silent security hole.
 */
@Component
@ConditionalOnProperty(name = "momstarter.dev.auto-verify-email", havingValue = "true")
public class DevModeGuard {

    private static final Logger log = LoggerFactory.getLogger(DevModeGuard.class);

    private final Environment environment;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    public DevModeGuard(Environment environment) {
        this.environment = environment;
    }

    /**
     * Called by Spring after field injection. Package-visible so unit tests can invoke it directly.
     */
    @PostConstruct
    void checkSafety() {
        String[] activeProfiles = environment.getActiveProfiles();
        boolean hasProdProfile = Arrays.stream(activeProfiles)
                .anyMatch(p -> p.equalsIgnoreCase("prod"));
        boolean isRemoteDatabase = !isLocalUrl(datasourceUrl);

        if (hasProdProfile || isRemoteDatabase) {
            String message = String.format(
                    "DEV MODE (momstarter.dev.auto-verify-email=true) must NOT run outside a local " +
                    "environment. Active profiles: %s — datasource URL: [%s]. " +
                    "Disable the flag in the active profile configuration immediately.",
                    Arrays.toString(activeProfiles), datasourceUrl);

            log.error("""
                    ╔═══════════════════════════════════════════════════════════════════════╗
                    ║  SECURITY ALERT — DEV MODE IS ACTIVE IN A NON-LOCAL ENVIRONMENT!    ║
                    ║  momstarter.dev.auto-verify-email=true MUST be local-only.           ║
                    ║  Active profiles : {}
                    ║  Datasource URL  : {}
                    ║  Refusing to start. Set auto-verify-email=false to proceed.          ║
                    ╚═══════════════════════════════════════════════════════════════════════╝""",
                    Arrays.toString(activeProfiles), datasourceUrl);

            throw new IllegalStateException(message);
        }

        log.warn("""
                ╔═══════════════════════════════════════════════════════════════════════╗
                ║  DEV MODE IS ACTIVE — FOR LOCAL TESTING ONLY, NOT FOR PRODUCTION!   ║
                ║  momstarter.dev.auto-verify-email=true                               ║
                ║  New accounts will be immediately email-verified (no token flow).    ║
                ╚═══════════════════════════════════════════════════════════════════════╝""");
    }

    /**
     * Returns true when the datasource URL targets a local/in-process database so that
     * the guard can allow startup. Recognises localhost, 127.0.0.1, ::1, and H2 in-memory URLs.
     */
    private static boolean isLocalUrl(String url) {
        if (url == null || url.isBlank()) {
            // No URL configured — treat as local (test context, etc.)
            return true;
        }
        return url.contains("localhost")
                || url.contains("127.0.0.1")
                || url.contains("::1")
                || url.startsWith("jdbc:h2:");
    }
}
