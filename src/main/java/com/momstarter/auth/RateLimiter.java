package com.momstarter.auth;

import com.momstarter.error.ApiException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A simple fixed-window rate limiter (§4B/§H). Keyed by an arbitrary string (IP, email, or a
 * combination), it throws {@code 429 rate_limited} once a key exceeds its budget within the
 * window. MVP is in-memory + per-instance; a shared store (Redis) / edge WAF is the
 * multi-instance hardening (flagged to cloud-infra-engineer).
 */
@Service
public class RateLimiter {

    private final Clock clock;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    private record Window(int count, Instant resetAt) {
    }

    /** Count one hit against {@code key}; throw 429 rate_limited if it exceeds maxRequests in the window. */
    public void check(String key, int maxRequests, Duration window) {
        Instant now = clock.instant();
        Window updated = windows.compute(key, (k, w) -> {
            if (w == null || !now.isBefore(w.resetAt())) {
                return new Window(1, now.plus(window));
            }
            return new Window(w.count() + 1, w.resetAt());
        });
        if (updated.count() > maxRequests) {
            throw new ApiException(429, "rate_limited");
        }
    }
}
