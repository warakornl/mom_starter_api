package com.momstarter.account;

import com.momstarter.account.dto.AccountInput;
import com.momstarter.account.dto.AccountResponse;
import com.momstarter.auth.RefreshTokenService;
import com.momstarter.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for GET /account, PATCH /account, and DELETE /account.
 *
 * <p>Account is identity (api-contract N9 "Account & sync"), not a sync-collection member.
 * It is read via GET /account and mutated via direct-REST PATCH /account under If-Match.
 * The {@code <sync>}-style fields ({@code version}, {@code deletedAt}) serve optimistic
 * concurrency and PDPA soft-delete only — not multi-device content federation.
 *
 * <h2>DELETE /account — PDPA s.33 soft-delete + session revocation</h2>
 * <p>Sets {@code users.deleted_at}, marks {@code status = "deleted"}, and revokes all
 * refresh-token families. This closes the "no writer for users.deleted_at" gap flagged by
 * compliance-reviewer (consent-hardgate-erasure-design.md §2.6 blocker D).
 *
 * <p><strong>Hard-erasure seam (prod-gate blocker D — NOT in scope for this slice):</strong>
 * Full PDPA s.33 account-level erasure — cascade deletion of all child rows and the
 * {@code users} row itself — requires a dedicated "Account-Deletion Slice" hard-purge scheduler
 * with a confirmed FK-cascade order (to be designed by {@code database-engineer}).
 * {@code TombstoneGcService.purgeExpiredTombstones()} handles child-collection tombstones
 * after 180 days but does NOT include the {@code users} table (§2.4 / §2.6).
 * Until that scheduler is wired:
 * <ul>
 *   <li>The account row remains in the DB with {@code deleted_at} set.</li>
 *   <li>All sessions are revoked (refresh tokens) so users cannot refresh.</li>
 *   <li>Subsequent login is blocked by a {@code deleted_at} check in {@code AuthService.login()}.</li>
 *   <li>The JWT access-token window (≤15 min) is the remaining live window; POST to sensitive
 *       endpoints (sync, reports, export) already gate on consent + email-verified — see
 *       api-contract "Auth security model (C1–C9) §422".</li>
 * </ul>
 * See: {@code docs/security/consent-hardgate-erasure-design.md §2.6} for the full design.
 */
@Service
public class AccountService {

    /** Locale values accepted by the contract (api-contract AccountInput enum). */
    private static final Set<String> VALID_LOCALES = Set.of("th", "en");

    private final UserRepository users;
    private final RefreshTokenService refreshTokens;

    public AccountService(UserRepository users, RefreshTokenService refreshTokens) {
        this.users = users;
        this.refreshTokens = refreshTokens;
    }

    // -------------------------------------------------------------------------
    // GET /account
    // -------------------------------------------------------------------------

    /**
     * Returns the authenticated user's account (email, locale, status, emailVerified).
     *
     * <p>A soft-deleted user within the 15-minute access-token window receives
     * {@code 404 not_found} — same as a non-existent user (no additional enumeration).
     *
     * @param userId JWT subject
     * @throws ApiException 404 not_found when the user is absent or soft-deleted
     */
    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID userId) {
        return toResponse(requireActiveUser(userId));
    }

    // -------------------------------------------------------------------------
    // PATCH /account
    // -------------------------------------------------------------------------

    /**
     * Updates email and/or locale for the authenticated user.
     *
     * <p>Rules (api-contract B2 / PATCH /account):
     * <ol>
     *   <li>{@code If-Match} required — 428 {@code precondition_required} if absent;
     *       412 {@code precondition_failed} if malformed.</li>
     *   <li>Version mismatch — throws {@link StaleAccountException} (caller returns 409 with
     *       the current authoritative account body).</li>
     *   <li>Email change — normalized (trim + lowercase); if the new address is already
     *       registered to another user, returns 422 (non-enumerating — does not reveal
     *       that the address exists; contract-gap resolution documented in {@link AccountInput}).</li>
     *   <li>Email change — resets {@code emailVerified = false}.
     *       TODO: trigger email-re-verification (mirrors /auth/register pattern; out of scope
     *       for this slice).</li>
     *   <li>Locale — must be {@code th} or {@code en} → 422 if not.</li>
     * </ol>
     *
     * @param userId  JWT subject
     * @param input   PATCH body (both fields optional; null = no change)
     * @param ifMatch raw {@code If-Match} header value (may be null)
     * @return the updated (or unchanged) account
     * @throws ApiException          404 / 422 / 428 / 412
     * @throws StaleAccountException caller returns 409 with the current account body
     */
    @Transactional
    public AccountResponse patchAccount(UUID userId, AccountInput input, String ifMatch) {
        User user = requireActiveUser(userId);

        // If-Match required for optimistic concurrency (api-contract B2)
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(428, "precondition_required");
        }

        long clientVersion = parseIfMatch(ifMatch);
        if (clientVersion != user.getVersion()) {
            throw new StaleAccountException(toResponse(user));
        }

        boolean changed = false;

        // --- Email update ---
        if (input != null && input.email() != null && !input.email().isBlank()) {
            String newEmail = normaliseEmail(input.email());
            if (!newEmail.equals(user.getEmail())) {
                // Non-enumerating check: if another user owns this email, return 422.
                // We do NOT say "email already registered" to avoid revealing existence.
                if (users.existsByEmail(newEmail)) {
                    throw new ApiException(422, "validation_error");
                }
                user.setEmail(newEmail);
                // Changing email invalidates the existing verification; re-verify needed.
                // TODO: send re-verification email (mirrors /auth/register flow — follow-up).
                user.setEmailVerified(false);
                changed = true;
            }
        }

        // --- Locale update ---
        if (input != null && input.locale() != null && !input.locale().isBlank()) {
            if (!VALID_LOCALES.contains(input.locale())) {
                throw new ApiException(422, "validation_error");
            }
            if (!input.locale().equals(user.getLocale())) {
                user.setLocale(input.locale());
                changed = true;
            }
        }

        if (changed) {
            user = users.saveAndFlush(user);
        }

        return toResponse(user);
    }

    // -------------------------------------------------------------------------
    // DELETE /account
    // -------------------------------------------------------------------------

    /**
     * Soft-deletes the account: sets {@code users.deleted_at}, marks {@code status = "deleted"},
     * and revokes all refresh-token families (blocking further login and refresh).
     *
     * <p>Idempotent: a second call on an already-deleted account is a silent no-op.
     *
     * <p>Access tokens already issued remain technically valid for up to 15 minutes after
     * deletion. During that window, GET /account returns {@code 404 not_found} (this service's
     * {@link #requireActiveUser} guard) and subsequent login is blocked by
     * {@link com.momstarter.auth.AuthService#login}.
     *
     * <p>Hard-erasure seam — see class-level Javadoc for the full PDPA s.33 design note.
     *
     * @param userId JWT subject
     * @throws ApiException 404 not_found if the user row itself is absent
     */
    @Transactional
    public void deleteAccount(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(404, "not_found"));

        // Idempotent: already soft-deleted — no-op
        if (user.getDeletedAt() != null) {
            return;
        }

        user.setDeletedAt(Instant.now());
        user.setStatus("deleted");
        users.saveAndFlush(user);

        // Revoke every refresh-token family: blocks all devices from refreshing.
        // Access tokens are stateless (≤15 min) and expire naturally.
        refreshTokens.revokeAllForUser(userId);

        // -----------------------------------------------------------------------
        // TODO — Hard-erasure seam (PDPA s.33 prod-gate blocker D):
        //
        // At this point the account is soft-deleted and all sessions are revoked.
        // Full erasure (cascading deletion of child rows → then the users row) is
        // a separate scheduled job that must be designed with database-engineer to
        // confirm the FK cascade order before implementation.
        //
        // Relevant classes and docs:
        //   - TombstoneGcService.purgeExpiredTombstones()  — child-collection GC (not yet scheduled)
        //   - docs/security/consent-hardgate-erasure-design.md §2.6 (blocker D design)
        // -----------------------------------------------------------------------
    }

    // -------------------------------------------------------------------------
    // Package-accessible helpers
    // -------------------------------------------------------------------------

    /**
     * Looks up the user and guards against soft-deleted accounts.
     * Returns {@code 404 not_found} for both missing and deleted users (no additional enumeration).
     */
    User requireActiveUser(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(404, "not_found"));
        if (user.getDeletedAt() != null) {
            throw new ApiException(404, "not_found");
        }
        return user;
    }

    /**
     * Maps a {@link User} entity to the wire response.
     * NEVER includes passwordHash or any credential material.
     */
    public static AccountResponse toResponse(User user) {
        return new AccountResponse(
                user.getId(),
                user.getEmail(),
                user.getLocale(),
                user.getStatus(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getVersion(),
                user.getDeletedAt()
        );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String normaliseEmail(String email) {
        return email.trim().toLowerCase();
    }

    /**
     * Parses the {@code If-Match} header value to a version number.
     * The contract sends {@code If-Match: "<version>"} (with optional surrounding quotes).
     */
    private static long parseIfMatch(String ifMatch) {
        try {
            return Long.parseLong(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new ApiException(412, "precondition_failed");
        }
    }
}
