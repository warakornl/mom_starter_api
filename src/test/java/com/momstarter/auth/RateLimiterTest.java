package com.momstarter.auth;

import com.momstarter.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Test
    void allowsUpToLimitThenThrows429() {
        RateLimiter rl = new RateLimiter(new MutableClock(Instant.parse("2026-06-29T00:00:00Z")));

        for (int i = 0; i < 5; i++) {
            rl.check("ip:1.2.3.4", 5, WINDOW);
        }
        assertThatThrownBy(() -> rl.check("ip:1.2.3.4", 5, WINDOW))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("rate_limited");
    }

    @Test
    void windowResetsAfterItElapses() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-29T00:00:00Z"));
        RateLimiter rl = new RateLimiter(clock);
        for (int i = 0; i < 5; i++) {
            rl.check("k", 5, WINDOW);
        }

        clock.advance(WINDOW.plusSeconds(1));

        assertThatCode(() -> rl.check("k", 5, WINDOW)).doesNotThrowAnyException();
    }

    @Test
    void differentKeysAreCountedIndependently() {
        RateLimiter rl = new RateLimiter(new MutableClock(Instant.parse("2026-06-29T00:00:00Z")));
        for (int i = 0; i < 5; i++) {
            rl.check("ip:1.1.1.1", 5, WINDOW);
        }
        assertThatCode(() -> rl.check("ip:2.2.2.2", 5, WINDOW)).doesNotThrowAnyException();
    }
}
