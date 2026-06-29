package com.momstarter.pregnancy;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link PregnancyProfile}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 in PostgreSQL mode, see
 * application-test.properties). Mirrors the pattern in {@code AuthIdentityRepositoryTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class PregnancyProfileRepositoryTest {

    @Autowired
    private PregnancyProfileRepository profiles;

    @Autowired
    private UserRepository users;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private User savedUser(String email) {
        User u = new User();
        u.setEmail(email);
        return users.save(u);
    }

    private PregnancyProfile buildProfile(java.util.UUID userId, LocalDate edd, String eddBasis) {
        PregnancyProfile p = new PregnancyProfile();
        p.setUserId(userId);
        p.setEdd(edd);
        p.setEddBasis(eddBasis);
        return p;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * A saved profile can be retrieved by userId; all mandatory fields are populated;
     * lifecycle defaults to "pregnant".
     */
    @Test
    void savesAndFindsByUserId() {
        User u = savedUser("mom@example.com");
        LocalDate edd = LocalDate.of(2027, 3, 15);

        PregnancyProfile saved = profiles.save(buildProfile(u.getId(), edd, "due_date"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getLifecycle()).isEqualTo("pregnant");
        assertThat(saved.getEdd()).isEqualTo(edd);
        assertThat(saved.getEddBasis()).isEqualTo("due_date");

        Optional<PregnancyProfile> found = profiles.findByUserId(u.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getEdd()).isEqualTo(edd);
    }

    /**
     * findByUserIdAndDeletedAtIsNull excludes soft-deleted profiles; findByUserId
     * still returns the tombstone so pull-replication can propagate the deletion.
     */
    @Test
    void findByUserIdAndDeletedAtIsNull_excludesSoftDeleted() {
        User u = savedUser("mom2@example.com");
        PregnancyProfile p = profiles.save(buildProfile(u.getId(), LocalDate.of(2027, 4, 1), "current_week"));

        p.setDeletedAt(Instant.now());
        profiles.save(p);

        assertThat(profiles.findByUserIdAndDeletedAtIsNull(u.getId())).isEmpty();
        assertThat(profiles.findByUserId(u.getId())).isPresent(); // tombstone still accessible
    }

    /**
     * The UNIQUE constraint on user_id rejects a second profile for the same user.
     * One profile per user is a hard data-model invariant (data-model §3.1).
     */
    @Test
    void uniqueUserIdConstraint_rejectsSecondProfile() {
        User u = savedUser("mom3@example.com");
        profiles.save(buildProfile(u.getId(), LocalDate.of(2027, 5, 10), "due_date"));

        assertThatThrownBy(() -> {
            profiles.save(buildProfile(u.getId(), LocalDate.of(2027, 6, 20), "due_date"));
            profiles.flush(); // force SQL execution within the transaction so the DB fires the constraint
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The JPA @Version field starts at 0 on the first save (api-contract B2: server-assigned,
     * monotonic, starts at 0).
     */
    @Test
    void version_startsAtZero() {
        User u = savedUser("mom4@example.com");
        PregnancyProfile p = profiles.save(buildProfile(u.getId(), LocalDate.of(2027, 7, 1), "due_date"));
        assertThat(p.getVersion()).isEqualTo(0L);
    }
}
