package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.dto.AuthTokens;
import com.momstarter.auth.dto.RegisterRequest;
import com.momstarter.auth.dto.VerifyEmailRequest;
import com.momstarter.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Two-phase, strictly non-enumerating registration (§E/§G). Register mints NO session and its
 * outcome is identical whether the email is new or already taken: a new email creates an
 * unverified account and emails a verification link; a colliding email creates/overwrites
 * nothing and instead emails the existing owner a notice. Password validation runs first and
 * may safely return a specific 422 (it describes the submitted password, not account existence).
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final EmailVerificationService emailVerification;
    private final VerificationEmailSender emailSender;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final RateLimiter rateLimiter;
    private final int registerMaxPerIpPerMin;
    private final int resendMaxPerIpPerMin;
    /** DEV ONLY — set via momstarter.dev.auto-verify-email (default false, true only in local profile). */
    private final boolean autoVerifyEmail;

    public RegistrationService(UserRepository users,
                               PasswordEncoder encoder,
                               PasswordPolicy passwordPolicy,
                               EmailVerificationService emailVerification,
                               VerificationEmailSender emailSender,
                               JwtService jwt,
                               RefreshTokenService refreshTokens,
                               RateLimiter rateLimiter,
                               @Value("${momstarter.ratelimit.register-per-ip-per-min:15}") int registerMaxPerIpPerMin,
                               @Value("${momstarter.ratelimit.resend-per-ip-per-min:10}") int resendMaxPerIpPerMin,
                               @Value("${momstarter.dev.auto-verify-email:false}") boolean autoVerifyEmail) {
        this.users = users;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.emailVerification = emailVerification;
        this.emailSender = emailSender;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.rateLimiter = rateLimiter;
        this.registerMaxPerIpPerMin = registerMaxPerIpPerMin;
        this.resendMaxPerIpPerMin = resendMaxPerIpPerMin;
        this.autoVerifyEmail = autoVerifyEmail;
    }

    public void register(RegisterRequest req, String clientIp) {
        // per-IP throttle — also caps the bcrypt/Argon2 cost now paid on every path (anti-DoS)
        rateLimiter.check("register-ip:" + clientIp, registerMaxPerIpPerMin, Duration.ofMinutes(1));

        passwordPolicy.validate(req.password());

        String email = normaliseEmail(req.email());
        // Hash on EVERY path so the bcrypt cost (the dominant timing term) is paid whether or not
        // the email already exists — otherwise response time leaks existence (§E). The hash is only
        // persisted for a genuinely new account.
        String passwordHash = encoder.encode(req.password());

        Optional<User> existing = users.findByEmail(email);
        if (existing.isEmpty()) {
            User user = new User();
            user.setEmail(email);
            user.setPasswordHash(passwordHash);
            user.setLocale(req.locale());

            if (autoVerifyEmail) {
                // DEV MODE ONLY — skip the two-phase flow so testers can log in immediately.
                // This branch is guarded by DevModeGuard; it will never reach here in production.
                log.warn("DEV MODE: auto-verify-email is ON — setting emailVerified=true for {} without email verification",
                        email);
                user.setEmailVerified(true);
                users.save(user);
                // No verification token issued, no email sent.
            } else {
                user.setEmailVerified(false);
                users.save(user);
                emailSender.sendVerification(email, emailVerification.issue(user));
            }
        } else {
            emailSender.sendAlreadyRegisteredNotice(email);
        }
        // no session is minted; the caller returns an identical 202 either way
    }

    /** Re-send the verification email. Always a no-op-or-send that the caller answers with 202 —
     *  never reveals whether the email exists or is already verified (§E/§H). */
    public void resendVerification(String email, String clientIp) {
        rateLimiter.check("resend-ip:" + clientIp, resendMaxPerIpPerMin, Duration.ofMinutes(1));

        String normalised = normaliseEmail(email);
        users.findByEmail(normalised).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                emailSender.sendVerification(normalised, emailVerification.issue(user));
            }
        });
    }

    /** Consume the emailed token, mark the account verified, and mint its FIRST session (§G). */
    public AuthTokens verifyEmail(VerifyEmailRequest req) {
        UUID userId = emailVerification.consume(req.token());
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(410, "verify_token_invalid"));
        user.setEmailVerified(true);
        users.save(user);

        RefreshTokenService.Issued issued = refreshTokens.mintFamily(user.getId(), req.deviceId(), null);
        String accessToken = jwt.issueAccessToken(user.getId(), true);
        return new AuthTokens(accessToken, issued.rawToken(),
                jwt.accessTtlSeconds(), RefreshTokenService.REFRESH_TTL.toSeconds());
    }

    static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
