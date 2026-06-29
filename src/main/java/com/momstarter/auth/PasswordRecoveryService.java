package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Account recovery (§5). {@code forgotPassword} is strictly non-enumerating: always returns the
 * same 202 to the caller, only actually issues a reset token + emails it when the account exists.
 * Rate-limited per IP (reset-email flooding defence).
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
    private final int forgotMaxPerIpPerMin;
    private final int resetMaxPerIpPerMin;

    public PasswordRecoveryService(UserRepository users,
                                   PasswordResetService passwordReset,
                                   PasswordEmailSender emailSender,
                                   RateLimiter rateLimiter,
                                   PasswordPolicy passwordPolicy,
                                   PasswordEncoder encoder,
                                   RefreshTokenService refreshTokens,
                                   @Value("${momstarter.ratelimit.forgot-per-ip-per-min:10}") int forgotMaxPerIpPerMin,
                                   @Value("${momstarter.ratelimit.reset-per-ip-per-min:10}") int resetMaxPerIpPerMin) {
        this.users = users;
        this.passwordReset = passwordReset;
        this.emailSender = emailSender;
        this.rateLimiter = rateLimiter;
        this.passwordPolicy = passwordPolicy;
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.forgotMaxPerIpPerMin = forgotMaxPerIpPerMin;
        this.resetMaxPerIpPerMin = resetMaxPerIpPerMin;
    }

    /** Complete a reset: validate the new password, consume the token, set the new hash, and
     *  revoke EVERY session (all devices), then notify the owner (§5). */
    public void resetPassword(String token, String newPassword, String clientIp) {
        rateLimiter.check("reset-ip:" + clientIp, resetMaxPerIpPerMin, Duration.ofMinutes(1));
        passwordPolicy.validate(newPassword);                       // 422 before burning the token

        UUID userId = passwordReset.consume(token);                 // 410 on bad/expired/used
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(410, "reset_token_invalid"));
        user.setPasswordHash(encoder.encode(newPassword));
        users.save(user);

        refreshTokens.revokeAllForUser(userId);                     // log out every device
        emailSender.sendPasswordChangedNotice(user.getEmail());
    }

    public void forgotPassword(String email, String clientIp) {
        rateLimiter.check("forgot-ip:" + clientIp, forgotMaxPerIpPerMin, Duration.ofMinutes(1));
        String normalised = normaliseEmail(email);
        users.findByEmail(normalised)
                .ifPresent(user -> emailSender.sendPasswordReset(normalised, passwordReset.issue(user)));
        // always returns; the caller emits an identical 202 whether or not the account exists
    }

    static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
