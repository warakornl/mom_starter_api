package com.momstarter.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    /** A hand-advanced clock so the 15-minute lock window is deterministic. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final String EMAIL = "mom@example.com";

    @Test
    void locksOnlyAfterReachingMaxFailures() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-29T00:00:00Z"));
        LoginAttemptService svc = new LoginAttemptService(clock);

        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            svc.recordFailure(EMAIL);
        }
        assertThat(svc.isLocked(EMAIL)).isFalse();

        svc.recordFailure(EMAIL); // the threshold failure
        assertThat(svc.isLocked(EMAIL)).isTrue();
    }

    @Test
    void resetClearsTheLock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-29T00:00:00Z"));
        LoginAttemptService svc = new LoginAttemptService(clock);
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            svc.recordFailure(EMAIL);
        }
        assertThat(svc.isLocked(EMAIL)).isTrue();

        svc.reset(EMAIL);

        assertThat(svc.isLocked(EMAIL)).isFalse();
    }

    @Test
    void lockExpiresAfterTheWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-29T00:00:00Z"));
        LoginAttemptService svc = new LoginAttemptService(clock);
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            svc.recordFailure(EMAIL);
        }
        assertThat(svc.isLocked(EMAIL)).isTrue();

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertThat(svc.isLocked(EMAIL)).isFalse();
    }

    @Test
    void normalisesEmailCaseAndWhitespace() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-29T00:00:00Z"));
        LoginAttemptService svc = new LoginAttemptService(clock);
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            svc.recordFailure("  MOM@Example.com ");
        }
        assertThat(svc.isLocked("mom@example.com")).isTrue();
    }
}
