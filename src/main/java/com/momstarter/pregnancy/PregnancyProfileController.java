package com.momstarter.pregnancy;

import com.momstarter.pregnancy.dto.PregnancyProfileInput;
import com.momstarter.pregnancy.dto.PregnancyProfileResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * REST controller for {@code GET /pregnancy-profile} and {@code PUT /pregnancy-profile}.
 *
 * <p>Context-path {@code /v1} is added by the server configuration; this controller maps
 * to {@code /v1/pregnancy-profile} in production.
 *
 * <p>Both endpoints require a valid Bearer JWT (enforced in {@code SecurityConfig} — this
 * path is not in the public list). The authenticated user id is the JWT {@code sub} claim.
 *
 * <p><strong>{@code X-Client-Date} header</strong> (optional, {@code YYYY-MM-DD}): the
 * device's local civil "today". Used to anchor the derived gestational-age snapshot and,
 * on PUT, the {@code currentWeek→edd} back-computation. Absent → server UTC civil date
 * fallback (advisory; see api-contract "Gestational-age &amp; stage computation — PINNED").
 *
 * <p>Requires the {@code PUT} path to call the service which enforces XOR validation,
 * consent gate, EDD window guard, and optimistic concurrency (see
 * {@link PregnancyProfileService}).
 */
@RestController
@RequestMapping("/pregnancy-profile")
public class PregnancyProfileController {

    private final PregnancyProfileService service;

    public PregnancyProfileController(PregnancyProfileService service) {
        this.service = service;
    }

    /**
     * GET /pregnancy-profile
     *
     * <p>Returns the user's live pregnancy profile with a derived gestational-age snapshot.
     * {@code 404 not_found} when no profile exists (client maps this to the Home
     * "Needs-onboarding" state — OQ-4).
     *
     * <p>Not consent-gated (read path — api-contract consent table).
     */
    @GetMapping
    public ResponseEntity<PregnancyProfileResponse> get(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Client-Date", required = false) String clientDateHeader) {

        UUID userId = UUID.fromString(jwt.getSubject());
        LocalDate clientDate = parseClientDate(clientDateHeader);
        PregnancyProfileResponse response = service.get(userId, clientDate);
        return ResponseEntity.ok(response);
    }

    /**
     * PUT /pregnancy-profile
     *
     * <p>Creates (→ 201) or updates (→ 200) the pregnancy profile.
     *
     * <ul>
     *   <li>XOR validation: exactly one of {@code edd} / {@code currentWeek} → 422 if violated.</li>
     *   <li>Consent gate: {@code general_health} must be granted → 403 if not (seam: always granted in MVP).</li>
     *   <li>EDD window guard: {@code clientDate−28d ≤ edd ≤ clientDate+308d} → 422 if violated.</li>
     *   <li>Create (no existing row): 201. No {@code If-Match} required.</li>
     *   <li>Update (row exists): {@code If-Match} required → 428 if absent; 409 with current body
     *       if stale; 200 otherwise (no-op if EDD unchanged, version NOT bumped).</li>
     * </ul>
     */
    @PutMapping
    public ResponseEntity<?> put(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Client-Date", required = false) String clientDateHeader,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) PregnancyProfileInput input) {

        UUID userId = UUID.fromString(jwt.getSubject());
        LocalDate clientDate = parseClientDate(clientDateHeader);

        PregnancyProfileService.PutResult result;
        try {
            result = service.put(userId, input, ifMatch, clientDate);
        } catch (StaleVersionException e) {
            // 409 Conflict: return the current authoritative record in the body
            // so the client can re-pull, re-apply its intent, and retry (B2).
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getCurrentProfile());
        }

        return switch (result) {
            case PregnancyProfileService.PutResult.Created c ->
                    ResponseEntity.status(HttpStatus.CREATED).body(c.profile());
            case PregnancyProfileService.PutResult.Updated u ->
                    ResponseEntity.ok(u.profile());
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses the {@code X-Client-Date} header ({@code YYYY-MM-DD}) to a {@link LocalDate}.
     * Falls back to the server's current UTC civil date when the header is absent or malformed.
     *
     * <p>The fallback is intentionally UTC (not the server's system zone) because the server is
     * a single-zone authority. The client SHOULD send this header on PUT especially for
     * {@code currentWeek} input to avoid a ±1-day drift in the stored EDD (api-contract OQ-2/7).
     */
    private static LocalDate parseClientDate(String header) {
        if (header == null || header.isBlank()) {
            return LocalDate.now(ZoneOffset.UTC);
        }
        try {
            return LocalDate.parse(header.trim());
        } catch (DateTimeParseException e) {
            // Malformed header → fall back to UTC (never a 400; the header is advisory)
            return LocalDate.now(ZoneOffset.UTC);
        }
    }
}
