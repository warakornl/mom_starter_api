package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.error.ApiException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * Lifecycle of the password-reset token (§5). Single-use, short-expiry (1h), stored only as
 * SHA-256. A bad / expired / already-used token resolves to one generic 410 reset_token_invalid
 * (never distinguishes wrong vs expired vs used — avoids token-probing oracles).
 */
@Service
public class PasswordResetService {

    static final Duration TTL = Duration.ofHours(1);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final PasswordResetTokenRepository tokens;
    private final Clock clock;

    public PasswordResetService(PasswordResetTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /** Issue a fresh token for the user and return the RAW value (to be emailed, never logged). */
    public String issue(User user) {
        String raw = generateRawToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setTokenHash(RefreshTokenService.sha256Hex(raw));
        token.setExpiresAt(clock.instant().plus(TTL));
        tokens.save(token);
        return raw;
    }

    /** Consume a token, returning the owning userId; bad/expired/used -> 410 reset_token_invalid. */
    public UUID consume(String rawToken) {
        PasswordResetToken token = tokens.findByTokenHash(RefreshTokenService.sha256Hex(rawToken))
                .orElseThrow(() -> new ApiException(410, "reset_token_invalid"));
        if (token.getConsumedAt() != null || token.getExpiresAt().isBefore(clock.instant())) {
            throw new ApiException(410, "reset_token_invalid");
        }
        token.setConsumedAt(clock.instant());
        tokens.save(token);
        return token.getUserId();
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }
}
