package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * Account recovery (§5). {@code forgotPassword} is strictly non-enumerating: always returns the
 * same 202 to the caller. The email-dispatch work is offloaded to {@link ForgotPasswordWorker}
 * which runs asynchronously — the HTTP path therefore takes the same time whether or not the
 * account exists (BE-CORE-1 constant-time / timing-oracle fix, contract §E).
 *
 * <p>Rate-limited per IP <em>and</em> per account (email) as required by contract §H (BE-CORE-9).
 */
@Service
public class PasswordRecoveryService {

    private final UserRepository users;
    private final PasswordResetService passwordReset;
    private final PasswordEmailSender emailSender;
    private final RateLimiter rateLimiter;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder encoder;
    private final RefreshTokenService refreshTokens;
    private final LoginAttemptService loginAttempts;
    private final ForgotPasswordWorker forgotWorker;
    private final int forgotMaxPerIpPerMin;
    private final int forgotMaxPerAccountPerMin;
    private final int resetMaxPerIpPerMin;

    public PasswordRecoveryService(UserRepository users,
                                   PasswordResetService passwordReset,
                                   PasswordEmailSender emailSender,
                                   RateLimiter rateLimiter,
                                   PasswordPolicy passwordPolicy,
                                   PasswordEncoder encoder,
                                   RefreshTokenService refreshTokens,
                                   LoginAttemptService loginAttempts,
                                   ForgotPasswordWorker forgotWorker,
                                   @Value("${momstarter.ratelimit.forgot-per-ip-per-min:10}") int forgotMaxPerIpPerMin,
                                   @Value("${momstarter.ratelimit.forgot-per-account-per-min:10}") int forgotMaxPerAccountPerMin,
                                   @Value("${momstarter.ratelimit.reset-per-ip-per-min:10}") int resetMaxPerIpPerMin) {
        this.users = users;
        this.passwordReset = passwordReset;
        this.emailSender = emailSender;
        this.rateLimiter = rateLimiter;
        this.passwordPolicy = passwordPolicy;
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.loginAttempts = loginAttempts;
        this.forgotWorker = forgotWorker;
        this.forgotMaxPerIpPerMin = forgotMaxPerIpPerMin;
        this.forgotMaxPerAccountPerMin = forgotMaxPerAccountPerMin;
        this.resetMaxPerIpPerMin = resetMaxPerIpPerMin;
    }

    /** Authenticated password change (§5): verify the current password (soft-locked against
     *  guessing), validate the new one, set it, revoke every OTHER device family (keeping the
     *  caller's {@code deviceId}), and notify the owner. */
    public void changePassword(UUID userId, String currentPassword, String newPassword, String deviceId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(401, "invalid_credentials"));

        String lockKey = "changepw:" + userId;
        if (loginAttempts.isLocked(lockKey)) {
            // Contract §H: soft-lock returns 429 rate_limited uniformly (never account_locked).
            throw new ApiException(429, "rate_limited");
        }
        if (user.getPasswordHash() == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
            loginAttempts.recordFailure(lockKey);
            throw new ApiException(401, "invalid_credentials");
        }
        loginAttempts.reset(lockKey);

        passwordPolicy.validate(newPassword);
        user.setPasswordHash(encoder.encode(newPassword));
        users.save(user);

        if (deviceId != null && !deviceId.isBlank()) {
            refreshTokens.revokeAllForUserExceptDevice(userId, deviceId);
        } else {
            refreshTokens.revokeAllForUser(userId);
        }
        emailSender.sendPasswordChangedNotice(user.getEmail());
    }

    /**
     * Complete a reset: validate the new password, atomically consume the token, set the new
     * password hash, and revoke EVERY session (all devices) — all within ONE transaction so that
     * a crash between steps cannot burn the token without updating the password (BE-CORE-7).
     * The owner notification is sent after the method returns (stub: in-process log).
     */
    @Transactional
    public void resetPassword(String token, String newPassword, String clientIp) {
        rateLimiter.check("reset-ip:" + clientIp, resetMaxPerIpPerMin, Duration.ofMinutes(1));
        passwordPolicy.validate(newPassword);           // 422 before touching the token (SEC-INV-6)

        UUID userId = passwordReset.consume(token);     // atomic CAS → 410 on bad/expired/used
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(410, "reset_token_invalid"));
        user.setPasswordHash(encoder.encode(newPassword));
        users.save(user);

        refreshTokens.revokeAllForUser(userId);         // log out every device (SEC-INV-4)
        // sendPasswordChangedNotice is a logging stub; call it after the TX work is done.
        // In production (real SES), move to @TransactionalEventListener(AFTER_COMMIT).
        emailSender.sendPasswordChangedNotice(user.getEmail());
    }

    /**
     * Forgot-password trigger (BE-CORE-1 — constant-time). The HTTP path is constant regardless
     * of account existence: it applies both rate limits and then enqueues an async job.
     * The {@link ForgotPasswordWorker} runs on a thread pool and decides whether to
     * issue+send (account exists) or silently complete (account not found). The 202 latency
     * therefore does not branch on whether the email has an account (timing oracle closed).
     *
     * <p>Per-IP <em>and</em> per-account buckets both checked here (BE-CORE-9, contract §H).
     */
    public void forgotPassword(String email, String clientIp) {
        rateLimiter.check("forgot-ip:" + clientIp, forgotMaxPerIpPerMin, Duration.ofMinutes(1));
        String normalised = normaliseEmail(email);
        // Per-account bucket (BE-CORE-9): bound reset-email flooding of one victim address.
        // Applied to the normalised email regardless of whether the account exists.
        rateLimiter.check("forgot-email:" + normalised, forgotMaxPerAccountPerMin, Duration.ofMinutes(1));
        // Enqueue the async worker — HTTP thread returns immediately (constant-time path).
        forgotWorker.dispatch(normalised);
    }

    static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
