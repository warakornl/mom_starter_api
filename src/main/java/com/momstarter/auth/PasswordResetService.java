package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.error.ApiException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

    /**
     * Atomically consume a token using a CAS UPDATE (BE-CORE-6). Returns the owning userId on
     * success. Throws {@code 410 reset_token_invalid} uniformly for missing / expired / already-
     * consumed tokens — no oracle. The single UPDATE statement eliminates the TOCTOU window
     * present in a two-step read-then-write pattern: when two concurrent requests submit the same
     * token, exactly one UPDATE affects 1 row (wins → 204); the other affects 0 rows (loses → 410).
     */
    public UUID consume(String rawToken) {
        String hash = RefreshTokenService.sha256Hex(rawToken);
        Instant now = clock.instant();

        // Atomic CAS: SET consumed_at=now WHERE consumed_at IS NULL AND expires_at > now
        // Returns 1 (won), 0 (already consumed / expired / not found) — no TOCTOU window.
        int rows = tokens.atomicConsume(hash, now);
        if (rows == 0) {
            throw new ApiException(410, "reset_token_invalid");
        }

        // CAS succeeded — clearAutomatically=true on the @Modifying ensures fresh read here.
        return tokens.findByTokenHash(hash)
                .map(PasswordResetToken::getUserId)
                .orElseThrow(() -> new ApiException(410, "reset_token_invalid"));
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }
}
