package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class PasswordResetServiceTest {

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @Autowired
    private PasswordResetTokenRepository repo;
    @Autowired
    private UserRepository users;

    private MutableClock clock;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-06-29T00:00:00Z"));
        service = new PasswordResetService(repo, clock);
    }

    private User seedUser() {
        User u = new User();
        u.setEmail("mom+" + UUID.randomUUID() + "@example.com");
        users.save(u);
        return u;
    }

    @Test
    void issueThenConsume_returnsUserId_onceOnly() {
        User u = seedUser();
        String raw = service.issue(u);

        assertThat(service.consume(raw)).isEqualTo(u.getId());

        assertThatThrownBy(() -> service.consume(raw))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("reset_token_invalid");
    }

    @Test
    void expiredToken_isInvalid() {
        User u = seedUser();
        String raw = service.issue(u);

        clock.advance(Duration.ofHours(1).plusMinutes(1));

        assertThatThrownBy(() -> service.consume(raw))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("reset_token_invalid");
    }

    @Test
    void unknownToken_isInvalid() {
        assertThatThrownBy(() -> service.consume("never-issued"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("reset_token_invalid");
    }

    @Test
    void tokenIsStoredOnlyAsSha256() {
        User u = seedUser();
        String raw = service.issue(u);

        assertThat(repo.findByTokenHash(raw)).isEmpty();
        assertThat(repo.findByTokenHash(RefreshTokenService.sha256Hex(raw))).isPresent();
    }
}
