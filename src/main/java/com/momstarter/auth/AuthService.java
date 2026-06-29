package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.dto.AuthTokens;
import com.momstarter.auth.dto.LoginRequest;
import com.momstarter.auth.dto.LogoutRequest;
import com.momstarter.auth.dto.RefreshRequest;
import com.momstarter.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Login orchestration (§E/§G/§H). Strictly non-enumerating: unknown-email and wrong-password
 * both return one generic {@code 401 invalid_credentials}, and a bcrypt comparison is always
 * run (against a dummy hash when the account is absent) so response timing never branches on
 * existence.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final LoginAttemptService loginAttempts;
    private final RateLimiter rateLimiter;
    private final int loginMaxPerIpPerMin;

    /** A real password hash compared against when the account is absent (constant-time defence). */
    private final String dummyHash;

    public AuthService(UserRepository users,
                       PasswordEncoder encoder,
                       JwtService jwt,
                       RefreshTokenService refreshTokens,
                       LoginAttemptService loginAttempts,
                       RateLimiter rateLimiter,
                       @Value("${momstarter.ratelimit.login-per-ip-per-min:20}") int loginMaxPerIpPerMin) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.loginAttempts = loginAttempts;
        this.rateLimiter = rateLimiter;
        this.loginMaxPerIpPerMin = loginMaxPerIpPerMin;
        this.dummyHash = encoder.encode("dummy-" + UUID.randomUUID());
    }

    public AuthTokens login(LoginRequest req, String clientIp) {
        // per-IP throttle (credential-stuffing / spraying defence, §4B.1) — before any work
        rateLimiter.check("login-ip:" + clientIp, loginMaxPerIpPerMin, Duration.ofMinutes(1));

        String email = normaliseEmail(req.email());

        if (loginAttempts.isLocked(email)) {
            // Contract §H: soft-lock returns 429 rate_limited (never account_locked) so all
            // 429 cases are uniform — mobile client diffs on rate_limited for user-facing copy.
            throw new ApiException(429, "rate_limited");
        }

        Optional<User> maybe = users.findByEmail(email);
        if (maybe.isEmpty()) {
            encoder.matches(req.password(), dummyHash); // burn the same time as a real check
            throw new ApiException(401, "invalid_credentials");
        }

        User user = maybe.get();
        // a password-less (e.g. future federated-only) account cannot log in by password
        if (user.getPasswordHash() == null || !encoder.matches(req.password(), user.getPasswordHash())) {
            loginAttempts.recordFailure(email);
            throw new ApiException(401, "invalid_credentials");
        }

        loginAttempts.reset(email);
        RefreshTokenService.Issued issued = refreshTokens.mintFamily(user.getId(), req.deviceId(), null);
        String accessToken = jwt.issueAccessToken(user.getId(), user.isEmailVerified());
        return new AuthTokens(accessToken, issued.rawToken(),
                jwt.accessTtlSeconds(), RefreshTokenService.REFRESH_TTL.toSeconds());
    }

    /** Rotate a refresh token (with reuse-detection) and mint a fresh access token. */
    public AuthTokens refresh(RefreshRequest req) {
        RefreshTokenService.Issued issued = refreshTokens.rotate(req.refreshToken(), req.deviceId());
        User user = users.findById(issued.userId())
                .orElseThrow(() -> new ApiException(401, "invalid_token"));
        String accessToken = jwt.issueAccessToken(user.getId(), user.isEmailVerified());
        return new AuthTokens(accessToken, issued.rawToken(),
                jwt.accessTtlSeconds(), RefreshTokenService.REFRESH_TTL.toSeconds());
    }

    /** The user's active device sessions ("devices signed in"). */
    public java.util.List<com.momstarter.auth.dto.DeviceSession> listSessions(UUID userId) {
        return refreshTokens.listSessions(userId);
    }

    /** Server-side logout: revoke the presented device family, or every family with allDevices. */
    public void logout(LogoutRequest req, UUID userId) {
        if (req != null && Boolean.TRUE.equals(req.allDevices())) {
            refreshTokens.revokeAllForUser(userId);
        } else if (req != null && req.refreshToken() != null && !req.refreshToken().isBlank()) {
            refreshTokens.revokeByRawToken(req.refreshToken());
        }
    }

    /** Revoke one device's session ("sign out that tablet"). */
    public void revokeDevice(UUID userId, String deviceId) {
        refreshTokens.revokeDevice(userId, deviceId);
    }

    /** Revoke every session for the user (DELETE /auth/sessions). */
    public void logoutAllDevices(UUID userId) {
        refreshTokens.revokeAllForUser(userId);
    }

    static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
