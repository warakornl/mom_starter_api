package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.dto.AuthTokens;
import com.momstarter.auth.dto.LoginRequest;
import com.momstarter.auth.dto.LogoutRequest;
import com.momstarter.auth.dto.RefreshRequest;
import com.momstarter.error.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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

    /** A real bcrypt hash compared against when the account is absent (constant-time defence). */
    private final String dummyHash;

    public AuthService(UserRepository users,
                       PasswordEncoder encoder,
                       JwtService jwt,
                       RefreshTokenService refreshTokens,
                       LoginAttemptService loginAttempts) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.loginAttempts = loginAttempts;
        this.dummyHash = encoder.encode("dummy-" + UUID.randomUUID());
    }

    public AuthTokens login(LoginRequest req, String clientIp) {
        String email = normaliseEmail(req.email());

        if (loginAttempts.isLocked(email)) {
            throw new ApiException(429, "account_locked");
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

    /** Server-side logout: revoke the presented device family, or every family with allDevices. */
    public void logout(LogoutRequest req, UUID userId) {
        if (req != null && Boolean.TRUE.equals(req.allDevices())) {
            refreshTokens.revokeAllForUser(userId);
        } else if (req != null && req.refreshToken() != null && !req.refreshToken().isBlank()) {
            refreshTokens.revokeByRawToken(req.refreshToken());
        }
    }

    static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
