package com.momstarter.account;

import com.momstarter.account.dto.AccountInput;
import com.momstarter.account.dto.AccountResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for the account self-management surface (api-contract N9):
 * <ul>
 *   <li>{@code GET    /account} — read the authenticated user's account</li>
 *   <li>{@code PATCH  /account} — update email and/or locale (If-Match required, B2)</li>
 *   <li>{@code DELETE /account} — soft-delete + session revocation (PDPA s.33, 202)</li>
 * </ul>
 *
 * <p>Context-path {@code /v1} is configured in {@code application.yml}; this controller
 * maps to {@code /v1/account} in production.
 *
 * <p>All three endpoints require a valid Bearer JWT (enforced by
 * {@link com.momstarter.config.SecurityConfig} — {@code .anyRequest().authenticated()}).
 *
 * <p>Account is identity, NOT a sync-collection member: it is never in the
 * {@code SyncChangeSet} (neither push-accepted nor pull-replicated). The
 * {@code <sync>}-style fields ({@code version}, {@code deletedAt}) serve optimistic
 * concurrency (If-Match) and PDPA soft-delete only — see "Account & sync (N9)".
 */
@RestController
@RequestMapping("/account")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // -------------------------------------------------------------------------
    // GET /account
    // -------------------------------------------------------------------------

    /**
     * Returns the authenticated user's account (email, locale, status, emailVerified).
     *
     * <p>Returns {@code 404 not_found} when the user is absent or soft-deleted
     * (e.g. within the 15-minute access-token window after {@code DELETE /account}).
     */
    @GetMapping
    public ResponseEntity<AccountResponse> get(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(accountService.getAccount(userId));
    }

    // -------------------------------------------------------------------------
    // PATCH /account
    // -------------------------------------------------------------------------

    /**
     * Updates the authenticated user's email and/or locale.
     *
     * <p>Both request-body fields are optional; a missing field means "no change".
     * Requires {@code If-Match: "<version>"} for optimistic concurrency (api-contract B2).
     *
     * <p>On success returns {@code 200} with the updated account.
     * On version mismatch returns {@code 409 Conflict} with the current authoritative
     * account body so the client can re-apply its intent and retry.
     *
     * <p>Error codes:
     * <ul>
     *   <li>{@code 428 precondition_required} — {@code If-Match} header absent</li>
     *   <li>{@code 412 precondition_failed} — {@code If-Match} value not parseable</li>
     *   <li>{@code 409 Conflict} — body = current account (version mismatch)</li>
     *   <li>{@code 422 validation_error} — invalid locale or email conflict (non-enumerating)</li>
     * </ul>
     */
    @PatchMapping
    public ResponseEntity<?> patch(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody AccountInput input) {
        UUID userId = UUID.fromString(jwt.getSubject());
        AccountResponse response;
        try {
            response = accountService.patchAccount(userId, input, ifMatch);
        } catch (StaleAccountException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getCurrentAccount());
        }
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // DELETE /account
    // -------------------------------------------------------------------------

    /**
     * Soft-deletes the account and revokes all sessions (PDPA s.33 erasure trigger).
     *
     * <p>Returns {@code 202 Accepted}: the soft-delete is synchronous; the hard-erasure
     * cascade is deferred (see {@link AccountService#deleteAccount} for the full design note).
     *
     * <p>Idempotent: a second call is a silent no-op (same 202).
     */
    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        accountService.deleteAccount(userId);
        return ResponseEntity.accepted().build();
    }
}
