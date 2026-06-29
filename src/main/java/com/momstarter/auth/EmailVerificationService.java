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
 * Lifecycle of the email-verification token (§G). Single-use, short-expiry, stored only as
 * SHA-256. A bad / expired / already-used token resolves to one generic 410 verify_token_invalid.
 */
@Service
public class EmailVerificationService {

    static final Duration TTL = Duration.ofHours(24);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final EmailVerificationTokenRepository tokens;
    private final Clock clock;

    public EmailVerificationService(EmailVerificationTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /** Issue a fresh token for the user and return the RAW value (to be emailed, never logged). */
    public String issue(User user) {
        String raw = generateRawToken();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUserId(user.getId());
        token.setTokenHash(RefreshTokenService.sha256Hex(raw));
        token.setExpiresAt(clock.instant().plus(TTL));
        tokens.save(token);
        return raw;
    }

    /** Consume a token, returning the owning userId; bad/expired/used -> 410 verify_token_invalid. */
    public UUID consume(String rawToken) {
        EmailVerificationToken token = tokens.findByTokenHash(RefreshTokenService.sha256Hex(rawToken))
                .orElseThrow(() -> new ApiException(410, "verify_token_invalid"));
        if (token.getConsumedAt() != null || token.getExpiresAt().isBefore(clock.instant())) {
            throw new ApiException(410, "verify_token_invalid");
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
