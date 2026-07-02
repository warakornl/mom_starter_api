package com.momstarter.consent;

import com.momstarter.consent.dto.ConsentInput;
import com.momstarter.consent.dto.ConsentListResponse;
import com.momstarter.consent.dto.ConsentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * REST controller for the PDPA consent management surface.
 *
 * <ul>
 *   <li>{@code POST /account/consents} — record a grant or withdrawal (201)</li>
 *   <li>{@code GET  /account/consents} — list consent history (200, cursor-paginated)</li>
 * </ul>
 *
 * <p>Context-path {@code /v1} is configured in {@code application.yml}; these endpoints
 * are reachable at {@code /v1/account/consents} in production.
 *
 * <p>Both endpoints require a valid Bearer JWT ({@code .anyRequest().authenticated()}
 * in {@link com.momstarter.config.SecurityConfig}).
 *
 * <p><strong>Neither endpoint is gated by any {@code ConsentChecker}.</strong>
 * The contract (api-contract.md line 353) mandates that {@code POST /account/consents}
 * is always reachable so the user can grant or withdraw consent even when all other
 * data endpoints are blocked by the consent gate.
 *
 * <p>Locale is NOT a request-body field.  It is resolved from the {@code Accept-Language}
 * header by {@link ConsentService#normalizeLocale} before INSERT (design §3.1).
 */
@RestController
@RequestMapping("/account/consents")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    // -------------------------------------------------------------------------
    // POST /account/consents
    // -------------------------------------------------------------------------

    /**
     * Records a PDPA consent grant or withdrawal event.
     *
     * <p>This endpoint is <strong>ungated</strong> — it must be reachable even when the
     * user has not yet granted any consent (api-contract "Consent gating" note: the
     * {@code /account/consents} surface is excluded from all consent checks so the user
     * can always give or revoke consent).
     *
     * <p>The {@code Accept-Language} header is read but never validated: any value (including
     * absent or unparseable) is safely normalised to {@code 'th'} or {@code 'en'} by
     * {@link ConsentService#normalizeLocale} before the INSERT.
     *
     * @param jwt            authenticated user (Bearer JWT)
     * @param acceptLanguage client's preferred language (normalised server-side; optional)
     * @param input          validated request body
     * @return 201 with the persisted consent record
     */
    @PostMapping
    public ResponseEntity<ConsentResponse> record(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @Valid @RequestBody ConsentInput input) {
        UUID userId = UUID.fromString(jwt.getSubject());
        ConsentResponse response = consentService.record(userId, input, acceptLanguage);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // -------------------------------------------------------------------------
    // GET /account/consents
    // -------------------------------------------------------------------------

    /**
     * Returns the authenticated user's consent history, ordered by {@code granted_at DESC}
     * (most recent first), with cursor-based pagination.
     *
     * @param jwt    authenticated user (Bearer JWT)
     * @param cursor opaque pagination cursor (absent → first page)
     * @param limit  number of items per page (default 20, max 100)
     * @return 200 with the page of consent records and an optional {@code nextCursor}
     */
    @GetMapping
    public ResponseEntity<ConsentListResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        UUID userId = UUID.fromString(jwt.getSubject());
        ConsentListResponse response = consentService.list(userId, cursor, limit);
        return ResponseEntity.ok(response);
    }
}
