package com.momstarter.auth;

import com.momstarter.account.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

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
    private final int forgotMaxPerIpPerMin;

    public PasswordRecoveryService(UserRepository users,
                                   PasswordResetService passwordReset,
                                   PasswordEmailSender emailSender,
                                   RateLimiter rateLimiter,
                                   @Value("${momstarter.ratelimit.forgot-per-ip-per-min:10}") int forgotMaxPerIpPerMin) {
        this.users = users;
        this.passwordReset = passwordReset;
        this.emailSender = emailSender;
        this.rateLimiter = rateLimiter;
        this.forgotMaxPerIpPerMin = forgotMaxPerIpPerMin;
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
