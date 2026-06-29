package com.momstarter.sync;

import com.momstarter.auth.EmailVerifiedGuard;
import com.momstarter.sync.dto.SyncPullResponse;
import com.momstarter.sync.dto.SyncPushRequest;
import com.momstarter.sync.dto.SyncPushResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST endpoints for the offline-sync engine (api-contract "Offline-sync engine (PINNED)").
 *
 * <ul>
 *   <li>{@code POST  /v1/sync/push} — applies a client-side change batch (push path, §A / §1–§10).</li>
 *   <li>{@code GET   /v1/sync/pull} — returns the server-side change-set for the caller (pull path, §B / §9).</li>
 * </ul>
 *
 * <p>Authentication: JWT Bearer (Spring Security resource-server; path NOT in PUBLIC_PATHS).
 * Authorization: userId from {@code jwt.getSubject()} — never trusted from the request body.
 *
 * <p>PDPA / no-PII-in-logs: userId is never logged at INFO or below.
 *
 * <p>Egress precondition (§G): {@link EmailVerifiedGuard#requireVerified(Jwt)} is evaluated
 * <strong>before</strong> the {@code cloud_storage} consent gate on every cloud-egress endpoint.
 *
 * <p>TODO 🟡-7: byte-cap backstop — add {@code server.tomcat.max-swallow-size} /
 * {@code server.tomcat.max-http-form-post-size} in application.properties to enforce the 5 MB
 * decoded payload cap at the Tomcat layer (defense-in-depth alongside the service-level check).
 * Also consider adding a {@code Content-Length} validation filter before deserialization.
 */
@RestController
@RequestMapping("/sync")
class SyncController {

    private final SyncService syncService;
    private final EmailVerifiedGuard emailVerifiedGuard;

    SyncController(SyncService syncService, EmailVerifiedGuard emailVerifiedGuard) {
        this.syncService = syncService;
        this.emailVerifiedGuard = emailVerifiedGuard;
    }

    /**
     * Applies a push batch.
     *
     * <p>Request headers:
     * <ul>
     *   <li>{@code Authorization: Bearer <jwt>} — mandatory (handled by Spring Security)</li>
     *   <li>{@code Idempotency-Key: <uuid>} — optional; 24h idempotency window (OQ-SYNC-15)</li>
     *   <li>{@code Content-Length} — used for the 5 MB byte-cap check</li>
     * </ul>
     *
     * <p>Response:
     * <ul>
     *   <li>200 — {@link SyncPushResponse} with {@code applied[]}, {@code conflicts[]}, {@code rejected[]}</li>
     *   <li>400 — {@code missing_required_field} (lastPulledAt absent → Bean Validation)</li>
     *   <li>403 — {@code consent_required} (cloud_storage not granted)</li>
     *   <li>409 — {@code watermark_expired} (lastPulledAt older than 180 days)</li>
     *   <li>413 — {@code batch_too_large} (>1000 records or >5 MB)</li>
     * </ul>
     */
    @PostMapping("/push")
    ResponseEntity<SyncPushResponse> push(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SyncPushRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Content-Length", required = false, defaultValue = "-1") long contentLength
    ) {
        // Egress precondition §G: email_verified FIRST, then consent gate in service
        emailVerifiedGuard.requireVerified(jwt);
        UUID userId = UUID.fromString(jwt.getSubject());
        SyncPushResponse response = syncService.push(userId, request, idempotencyKey, contentLength);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the server-side change-set.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code since} — ISO-8601 Instant or {@code 0} for cold start (absent = cold start).</li>
     *   <li>{@code cursor} — opaque continuation token for paginated drains (Base64-JSON, 1h TTL).</li>
     *   <li>{@code limit} — max records per batch [1, 5000]; default 1000.</li>
     *   <li>{@code safeWindow} — additional look-back seconds [0, 60]; default 5.</li>
     * </ul>
     *
     * <p>Response:
     * <ul>
     *   <li>200 — {@link SyncPullResponse} with {@code changes}, {@code timestamp} (W1), optional {@code nextCursor}</li>
     *   <li>400 — {@code invalid_cursor} (tampered, malformed, or expired cursor)</li>
     *   <li>403 — {@code consent_required} (cloud_storage not granted)</li>
     *   <li>409 — {@code watermark_expired} ({@code since} older than 180 days)</li>
     * </ul>
     */
    @GetMapping("/pull")
    ResponseEntity<SyncPullResponse> pull(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer safeWindow
    ) {
        // Egress precondition §G: email_verified FIRST, then consent gate in service
        emailVerifiedGuard.requireVerified(jwt);
        UUID userId = UUID.fromString(jwt.getSubject());
        SyncPullResponse response = syncService.pull(userId, since, safeWindow, cursor, limit);
        return ResponseEntity.ok(response);
    }
}
