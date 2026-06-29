package com.momstarter.auth;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-account login soft-lock (§H): after {@link #MAX_FAILURES} failures an account is
 * temporarily locked for {@link #LOCK_DURATION} — never permanently. A success calls
 * {@link #reset}.
 *
 * <p>MVP keeps counters in memory, keyed by the SHA-256 of the normalised email (so raw
 * emails are not held). A shared store (DB/Redis) for multi-instance is a later concern.
 */
@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 10;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final Clock clock;
    private final ConcurrentMap<String, Attempts> byEmailHash = new ConcurrentHashMap<>();

    public LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    private record Attempts(int count, Instant lockedUntil) {
    }

    public void recordFailure(String email) {
        Instant now = clock.instant();
        byEmailHash.compute(key(email), (k, cur) -> {
            int count = (cur == null ? 0 : cur.count()) + 1;
            Instant lockedUntil = count >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
            return new Attempts(count, lockedUntil);
        });
    }

    public boolean isLocked(String email) {
        String key = key(email);
        Attempts a = byEmailHash.get(key);
        if (a == null || a.lockedUntil() == null) {
            return false;
        }
        if (clock.instant().isBefore(a.lockedUntil())) {
            return true;
        }
        byEmailHash.remove(key); // window passed — clear so the counter starts fresh
        return false;
    }

    public void reset(String email) {
        byEmailHash.remove(key(email));
    }

    private static String key(String email) {
        String normalised = email == null ? "" : email.trim().toLowerCase();
        return RefreshTokenService.sha256Hex(normalised);
    }
}
