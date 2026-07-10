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

// RED: tests for entity name cipher fields + H2 shred (added by springboot-backend-dev name-fields slice)

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

    // -------------------------------------------------------------------------
    // Name cipher field tests (name-fields slice)
    // -------------------------------------------------------------------------

    /**
     * Entity name cipher fields can be set to non-null bytes and read back (round-trip via H2).
     * RED: will fail until PregnancyProfile gains the 3 bytea fields.
     */
    @Test
    void nameCipherFields_setAndRead_roundTrip() {
        User u = savedUser("mom5@example.com");
        PregnancyProfile p = buildProfile(u.getId(), LocalDate.of(2027, 8, 1), "due_date");
        p.setMotherFirstNameCipher(new byte[]{0x01, 0x02, 0x03});
        p.setMotherLastNameCipher(new byte[]{0x04, 0x05, 0x06});
        p.setBabyNameCipher(new byte[]{0x07, 0x08, 0x09});
        PregnancyProfile saved = profiles.save(p);
        profiles.flush();

        PregnancyProfile found = profiles.findByUserId(u.getId()).orElseThrow();
        assertThat(found.getMotherFirstNameCipher())
                .as("motherFirstNameCipher round-trip")
                .isEqualTo(new byte[]{0x01, 0x02, 0x03});
        assertThat(found.getMotherLastNameCipher())
                .as("motherLastNameCipher round-trip")
                .isEqualTo(new byte[]{0x04, 0x05, 0x06});
        assertThat(found.getBabyNameCipher())
                .as("babyNameCipher round-trip")
                .isEqualTo(new byte[]{0x07, 0x08, 0x09});
    }

    /**
     * shredCiphersByUserId NULLs all three name cipher columns on H2
     * (belt-and-suspenders for PDPA ม.33; real Postgres tested in PgSmokeTest).
     * RED: will fail until PregnancyProfile has the 3 fields and shred method works via entity.
     */
    @Test
    void shredCiphersByUserId_H2_nullsAllThreeNameCiphers() {
        User u = savedUser("mom6@example.com");
        PregnancyProfile p = buildProfile(u.getId(), LocalDate.of(2027, 9, 1), "due_date");
        p.setMotherFirstNameCipher(new byte[]{0x41, 0x6E, 0x6E, 0x61});    // "Anna"
        p.setMotherLastNameCipher(new byte[]{0x53, 0x6D, 0x69, 0x74, 0x68}); // "Smith"
        p.setBabyNameCipher(new byte[]{0x4C, 0x69, 0x6C, 0x79});             // "Lily"
        profiles.saveAndFlush(p);

        int shredded = profiles.shredCiphersByUserId(u.getId());
        assertThat(shredded).as("shred must affect exactly 1 row").isEqualTo(1);

        PregnancyProfile after = profiles.findByUserId(u.getId()).orElseThrow();
        assertThat(after.getMotherFirstNameCipher())
                .as("motherFirstNameCipher must be NULL after shred")
                .isNull();
        assertThat(after.getMotherLastNameCipher())
                .as("motherLastNameCipher must be NULL after shred")
                .isNull();
        assertThat(after.getBabyNameCipher())
                .as("babyNameCipher must be NULL after shred")
                .isNull();
    }

    // -------------------------------------------------------------------------
    // Hospital-stay cipher field tests (hospital-stay slice)
    // RED: will fail until PregnancyProfile gains hospitalAdmissionDateCipher / hospitalDischargeDateCipher
    // -------------------------------------------------------------------------

    /**
     * Entity hospital-stay cipher fields can be set to non-null bytes and read back (round-trip via H2).
     * RED: will fail until PregnancyProfile has the 2 bytea hospital fields.
     */
    @Test
    void hospitalCipherFields_setAndRead_roundTrip() {
        User u = savedUser("mom7@example.com");
        PregnancyProfile p = buildProfile(u.getId(), LocalDate.of(2027, 10, 1), "due_date");
        // MVP posture: raw UTF-8 civil-date bytes stored verbatim (no-op cipher)
        p.setHospitalAdmissionDateCipher("2027-09-28".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        p.setHospitalDischargeDateCipher("2027-10-01".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        profiles.saveAndFlush(p);

        PregnancyProfile found = profiles.findByUserId(u.getId()).orElseThrow();
        assertThat(found.getHospitalAdmissionDateCipher())
                .as("hospitalAdmissionDateCipher round-trip")
                .isEqualTo("2027-09-28".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(found.getHospitalDischargeDateCipher())
                .as("hospitalDischargeDateCipher round-trip")
                .isEqualTo("2027-10-01".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * shredCiphersByUserId NULLs ALL FIVE cipher columns (3 name + 2 hospital-stay) on H2
     * (PDPA ม.33 belt-and-suspenders shred; real Postgres covered by PgSmokeTest).
     * RED: will fail until PregnancyProfile has all 5 entity fields.
     */
    @Test
    void shredCiphersByUserId_H2_nullsAllFiveCiphers() {
        User u = savedUser("mom8@example.com");
        PregnancyProfile p = buildProfile(u.getId(), LocalDate.of(2027, 11, 1), "due_date");
        p.setMotherFirstNameCipher(new byte[]{0x41});  // "A"
        p.setMotherLastNameCipher(new byte[]{0x42});   // "B"
        p.setBabyNameCipher(new byte[]{0x43});          // "C"
        p.setHospitalAdmissionDateCipher(new byte[]{0x44}); // "D"
        p.setHospitalDischargeDateCipher(new byte[]{0x45}); // "E"
        profiles.saveAndFlush(p);

        int shredded = profiles.shredCiphersByUserId(u.getId());
        assertThat(shredded).as("shred must affect exactly 1 row").isEqualTo(1);

        PregnancyProfile after = profiles.findByUserId(u.getId()).orElseThrow();
        assertThat(after.getMotherFirstNameCipher())
                .as("motherFirstNameCipher must be NULL after shred").isNull();
        assertThat(after.getMotherLastNameCipher())
                .as("motherLastNameCipher must be NULL after shred").isNull();
        assertThat(after.getBabyNameCipher())
                .as("babyNameCipher must be NULL after shred").isNull();
        assertThat(after.getHospitalAdmissionDateCipher())
                .as("hospitalAdmissionDateCipher must be NULL after shred").isNull();
        assertThat(after.getHospitalDischargeDateCipher())
                .as("hospitalDischargeDateCipher must be NULL after shred").isNull();
    }
}
