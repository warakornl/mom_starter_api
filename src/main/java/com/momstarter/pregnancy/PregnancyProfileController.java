package com.momstarter.pregnancy;

import com.momstarter.pregnancy.dto.BirthEventInput;
import com.momstarter.pregnancy.dto.LossEventInput;
import com.momstarter.pregnancy.dto.PregnancyProfileInput;
import com.momstarter.pregnancy.dto.PregnancyProfileResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for the pregnancy-profile surface:
 * <ul>
 *   <li>{@code GET  /pregnancy-profile} — read profile with derived snapshot</li>
 *   <li>{@code PUT  /pregnancy-profile} — create or update EDD</li>
 *   <li>{@code POST /pregnancy-profile/birth-event} — pregnant → postpartum transition</li>
 * </ul>
 *
 * <p>Context-path {@code /v1} is added by the server configuration; this controller maps to
 * {@code /v1/pregnancy-profile*} in production.
 *
 * <p>All three endpoints require a valid Bearer JWT (enforced in {@code SecurityConfig}).
 * The authenticated user id is the JWT {@code sub} claim.
 *
 * <p><strong>{@code X-Client-Date} header</strong> (optional, {@code YYYY-MM-DD}): the device's
 * local civil "today". Used to anchor the derived snapshot. Absent → server UTC civil date fallback.
 * Clients MUST send it on {@code PUT} and on {@code POST /birth-event} (see api-contract OQ-2/7 and
 * "Birth-event &amp; postpartum counting — PINNED").
 */
@RestController
@RequestMapping("/pregnancy-profile")
public class PregnancyProfileController {

    private final PregnancyProfileService service;
    private final ProfileVerbIdempotencyStore idempotencyStore;

    public PregnancyProfileController(PregnancyProfileService service,
                                       ProfileVerbIdempotencyStore idempotencyStore) {
        this.service = service;
        this.idempotencyStore = idempotencyStore;
    }

    // -------------------------------------------------------------------------
    // GET /pregnancy-profile
    // -------------------------------------------------------------------------

    /**
     * Returns the user's live pregnancy profile with a derived snapshot.
     *
     * <p>When {@code lifecycle = "pregnant"} the snapshot contains gestational-age fields;
     * when {@code lifecycle = "postpartum"} it contains postpartum-age fields and gestational
     * fields are absent (null → excluded by {@code @JsonInclude(NON_NULL)}).
     *
     * <p>{@code 404 not_found} when no live profile exists (client maps this to the Home
     * "Needs-onboarding" state — OQ-4). Not consent-gated (read path).
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

    // -------------------------------------------------------------------------
    // PUT /pregnancy-profile
    // -------------------------------------------------------------------------

    /**
     * Creates (→ 201) or updates (→ 200) the pregnancy profile EDD.
     *
     * <p>Consent-gated: {@code general_health} must be granted → 403 if not.
     * XOR validation: exactly one of {@code edd} / {@code currentWeek} → 422 if violated.
     * EDD window guard: {@code clientDate−28d ≤ edd ≤ clientDate+308d} → 422 if violated.
     * {@code If-Match} required on update → 428 if absent; 409 with current body if stale.
     *
     * <p><strong>{@code Idempotency-Key}</strong> (optional, OR-BACKEND-1): when present and a
     * stored result already exists for {@code (userId, key)}, the ORIGINAL response (status +
     * body) is replayed verbatim and the mutation is NOT re-executed — a repeated key is the
     * SAME logical intent (functional-spec §8, OR-INV-4). A first-time key runs normally; only
     * its 2xx outcome is cached (a transient 409 is never cached/replayed).
     */
    @PutMapping
    public ResponseEntity<?> put(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Client-Date", required = false) String clientDateHeader,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PregnancyProfileInput input) {

        UUID userId = UUID.fromString(jwt.getSubject());
        LocalDate clientDate = parseClientDate(clientDateHeader);

        if (idempotencyKey != null) {
            Optional<ProfileVerbIdempotencyStore.StoredResult> replay =
                    idempotencyStore.get(userId, idempotencyKey);
            if (replay.isPresent()) {
                return ResponseEntity.status(replay.get().status()).body(replay.get().body());
            }
        }

        PregnancyProfileService.PutResult result;
        try {
            result = service.put(userId, input, ifMatch, clientDate);
        } catch (StaleVersionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getCurrentProfile());
        }

        ResponseEntity<?> response = switch (result) {
            case PregnancyProfileService.PutResult.Created c ->
                    ResponseEntity.status(HttpStatus.CREATED).body(c.profile());
            case PregnancyProfileService.PutResult.Updated u ->
                    ResponseEntity.ok(u.profile());
        };

        if (idempotencyKey != null) {
            idempotencyStore.put(userId, idempotencyKey,
                    response.getStatusCode().value(), response.getBody());
        }
        return response;
    }

    // -------------------------------------------------------------------------
    // POST /pregnancy-profile/birth-event
    // -------------------------------------------------------------------------

    /**
     * Records the birth event, transitioning the profile from {@code pregnant} to
     * {@code postpartum} (api-contract "Birth-event &amp; postpartum counting — PINNED", OQ-8).
     *
     * <p>Always returns {@code 200} on success (it mutates the existing profile row, not a create):
     * <ul>
     *   <li>Initial transition ({@code pregnant → postpartum}): stores {@code birthDate},
     *       optional {@code deliveryType}/{@code birthNote}, bumps {@code version}.</li>
     *   <li>Content-level no-op ({@code postpartum} + same {@code birthDate}): returns current
     *       record with {@code version} NOT bumped (OQ-12/PP6 idempotency).</li>
     *   <li>Correction ({@code postpartum} + differing {@code birthDate}): updates {@code birthDate}
     *       and bumps {@code version} (OQ-13/PP1).</li>
     * </ul>
     *
     * <p>Error codes:
     * <ul>
     *   <li>{@code 404 not_found} — no live profile for this user</li>
     *   <li>{@code 409 invalid_lifecycle_state (details:"ended")} — profile is already ended</li>
     *   <li>{@code 403 consent_required (details:"general_health")} — consent not granted</li>
     *   <li>{@code 428 precondition_required} — {@code If-Match} absent</li>
     *   <li>{@code 409} — {@code If-Match} version stale (body = current authoritative profile)</li>
     *   <li>{@code 422 validation_error} — birthDate null, future, or before {@code edd − 126d}</li>
     * </ul>
     *
     * <p>The {@code X-Client-Date} header MUST be sent by clients (protocol-optional, server falls
     * back to UTC, but a UTC fallback can false-reject a legitimate TH birth after local midnight).
     */
    @PostMapping("/birth-event")
    public ResponseEntity<?> birthEvent(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Client-Date", required = false) String clientDateHeader,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody BirthEventInput input) {

        UUID userId = UUID.fromString(jwt.getSubject());
        LocalDate clientDate = parseClientDate(clientDateHeader);

        PregnancyProfileResponse response;
        try {
            response = service.recordBirthEvent(userId, input, ifMatch, clientDate);
        } catch (StaleVersionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getCurrentProfile());
        }

        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // POST /pregnancy-profile/loss-event
    // -------------------------------------------------------------------------

    /**
     * Records a pregnancy-loss event, transitioning the profile from {@code pregnant} to
     * {@code ended} (pregnancy-loss-recording-functional-spec.md §7.1-§7.3). The ONLY
     * {@code pregnant → ended} transition (LOSS-INV-1) — reversed exclusively by
     * {@code POST /pregnancy-profile/reopen}.
     *
     * <p>Always returns {@code 200} on success (mutates the existing profile row, not a create).
     * In the SAME DB transaction the server also deactivates every reminder NOT flagged
     * {@code survives_ended} (reversible soft tombstone — LOSS-INV-3/4/5).
     *
     * <p>Error codes (functional-spec §7.1 P1-P8 truth table):
     * <ul>
     *   <li>{@code 403 consent_required (details:"general_health")}</li>
     *   <li>{@code 428 precondition_required} — {@code If-Match} absent</li>
     *   <li>{@code 412 precondition_failed} — {@code If-Match} unparseable</li>
     *   <li>{@code 404 not_found} — no live profile for this user</li>
     *   <li>{@code 409} — {@code If-Match} version stale (body = current authoritative profile)</li>
     *   <li>{@code 409 invalid_lifecycle_state (details:"postpartum")} — loss is pregnant-only</li>
     *   <li>{@code 422 validation_error (details: loss_date_range | loss_date_malformed)}</li>
     * </ul>
     *
     * <p>{@code lossDate} is OPTIONAL/skippable — an empty/absent body is a full success
     * (LOSS-INV-11). A structurally non-JSON body is rejected {@code 400 bad_request} by
     * {@code GlobalExceptionHandler} before this method is invoked (functional-spec §10.12).
     *
     * <p><strong>{@code Idempotency-Key}</strong> (optional, OR-BACKEND-1): a repeated key
     * replays the ORIGINAL stored 200 verbatim — a replayed loss-event must NOT re-throw
     * {@code 409 invalid_lifecycle_state} just because the first call already moved the
     * profile to {@code ended} (functional-spec §8, OR-INV-4). A first-time key runs normally
     * and its consent re-check is NOT bypassed; only a genuinely repeated key short-circuits.
     */
    @PostMapping("/loss-event")
    public ResponseEntity<?> lossEvent(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Client-Date", required = false) String clientDateHeader,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) LossEventInput input) {

        UUID userId = UUID.fromString(jwt.getSubject());
        LocalDate clientDate = parseClientDate(clientDateHeader);

        if (idempotencyKey != null) {
            Optional<ProfileVerbIdempotencyStore.StoredResult> replay =
                    idempotencyStore.get(userId, idempotencyKey);
            if (replay.isPresent()) {
                return ResponseEntity.status(replay.get().status()).body(replay.get().body());
            }
        }

        PregnancyProfileResponse response;
        try {
            response = service.recordLossEvent(userId, input, ifMatch, clientDate);
        } catch (StaleVersionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getCurrentProfile());
        }

        if (idempotencyKey != null) {
            idempotencyStore.put(userId, idempotencyKey, HttpStatus.OK.value(), response);
        }
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // POST /pregnancy-profile/reopen
    // -------------------------------------------------------------------------

    /**
     * Reverses a pregnancy-loss event, transitioning the profile from {@code ended} back to
     * {@code pregnant} (functional-spec §7.4, US-4 correction). Explicit, always-available —
     * NOT a timed undo. Any request body is ignored.
     *
     * <p>Always returns {@code 200} on success. In the SAME DB transaction the server clears
     * {@code loss_date := NULL} (S4/LOSS-INV-6) and re-activates exactly the reminders it
     * deactivated on the corresponding {@code loss-event} (LOSS-INV-3).
     *
     * <p>Error codes (functional-spec §7.4 P1-P7 truth table):
     * <ul>
     *   <li>{@code 403 consent_required (details:"general_health")}</li>
     *   <li>{@code 428 precondition_required} — {@code If-Match} absent</li>
     *   <li>{@code 412 precondition_failed} — {@code If-Match} unparseable</li>
     *   <li>{@code 404 not_found} — no live profile for this user</li>
     *   <li>{@code 409} — {@code If-Match} version stale (body = current authoritative profile)</li>
     *   <li>{@code 409 invalid_lifecycle_state (details:"postpartum")} — reopen is ended-only
     *       ({@code postpartum → pregnant} re-open stays deferred, OQ-13)</li>
     * </ul>
     *
     * <p><strong>{@code Idempotency-Key}</strong> (optional, OR-BACKEND-1): a repeated key
     * replays the ORIGINAL stored 200 verbatim instead of re-executing the reminder
     * re-activation sweep (functional-spec §8, OR-INV-4).
     */
    @PostMapping("/reopen")
    public ResponseEntity<?> reopen(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Client-Date", required = false) String clientDateHeader,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        UUID userId = UUID.fromString(jwt.getSubject());
        LocalDate clientDate = parseClientDate(clientDateHeader);

        if (idempotencyKey != null) {
            Optional<ProfileVerbIdempotencyStore.StoredResult> replay =
                    idempotencyStore.get(userId, idempotencyKey);
            if (replay.isPresent()) {
                return ResponseEntity.status(replay.get().status()).body(replay.get().body());
            }
        }

        PregnancyProfileResponse response;
        try {
            response = service.reopen(userId, ifMatch, clientDate);
        } catch (StaleVersionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getCurrentProfile());
        }

        if (idempotencyKey != null) {
            idempotencyStore.put(userId, idempotencyKey, HttpStatus.OK.value(), response);
        }
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses the {@code X-Client-Date} header ({@code YYYY-MM-DD}) to a {@link LocalDate}.
     * Falls back to the server's current UTC civil date when the header is absent or malformed.
     *
     * <p>The fallback is intentionally UTC (not the server's system zone) because the server is
     * a single-zone authority. Clients MUST send this header on PUT and birth-event to avoid
     * a ±1-day drift for TH users (UTC+7) — see api-contract OQ-2/7 and birth-event PINNED section.
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
