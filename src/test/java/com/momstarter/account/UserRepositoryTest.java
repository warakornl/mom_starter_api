package com.momstarter.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 / Task 0.2 — the auth-core schema (Flyway) and the User entity boot
 * against H2 in PostgreSQL mode, and email_verified defaults to false.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class UserRepositoryTest {

    @Autowired
    private UserRepository users;

    @Test
    void savesUserAndFindsByEmail_withEmailVerifiedDefaultingFalse() {
        User u = new User();
        u.setEmail("mom@example.com");
        u.setPasswordHash("{bcrypt}$2a$10$abcdefghijklmnopqrstuvDUMMYDUMMYDUMMYDUMMYDU");
        u.setLocale("th");
        users.save(u);

        Optional<User> found = users.findByEmail("mom@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isNotNull();
        assertThat(found.get().isEmailVerified()).isFalse();
        assertThat(found.get().getStatus()).isEqualTo("active");
    }
}
