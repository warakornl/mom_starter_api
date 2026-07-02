package com.momstarter.account;

import com.momstarter.consent.ConsentRecord;
import com.momstarter.consent.ConsentRecordRepository;
import com.momstarter.pregnancy.PregnancyProfile;
import com.momstarter.pregnancy.PregnancyProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AccountErasureService}.
 *
 * <p>Verifies the FK-safe cascade hard-purge of soft-deleted user accounts past the
 * retention window (PDPA ม.33, consent-hardgate-erasure-design.md §2.6).
 *
 * <p>FK-ordering correctness is implicitly tested: if {@code users} were deleted before
 * any FK-child table, the underlying DB (H2 PostgreSQL-mode, which enforces FK RESTRICT
 * the same as real PostgreSQL) would throw a constraint-violation exception, causing the
 * test to fail. A passing test proves children-before-parent ordering.
 *
 * <p>H2 notes: no jsonb columns are involved in the purge queries (plain JDBC DELETE WHERE
 * user_id = ?) — the h2-masks-jsonb-binding pitfall does not apply here. FK constraints
 * ARE enforced by H2 in PostgreSQL mode (ON DELETE RESTRICT is respected).
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Cascade deletes user + pregnancy_profile + consent_record in FK-safe order.</li>
 *   <li>Active users (deleted_at IS NULL) are not touched.</li>
 *   <li>Users soft-deleted within the retention window are not touched.</li>
 *   <li>Idempotent: second run after a successful purge returns 0.</li>
 *   <li>Returns correct count when multiple users are eligible.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Import(AccountErasureService.class)
class AccountErasureServiceTest {

    @Autowired private TestEntityManager em;
    @Autowired private AccountErasureService erasureService;
    @Autowired private UserRepository users;
    @Autowired private PregnancyProfileRepository profiles;
    @Autowired private ConsentRecordRepository consentRecords;

    // -------------------------------------------------------------------------
    // FK-safe cascade: children deleted before parent
    // -------------------------------------------------------------------------

    /**
     * A soft-deleted user past the retention window, plus child rows in
     * pregnancy_profile (FK to users) and consent_record (FK to users), must all
     * be hard-purged without triggering a FK constraint violation.
     *
     * <p>If the service deleted {@code users} before its FK-children, H2 would throw
     * a {@code DataIntegrityViolationException} — this test would fail.
     */
    @Test
    void purgeExpiredAccounts_cascadeDeletesChildRowsBeforeParent() {
        UUID userId = persistSoftDeletedUser("purge-cascade@example.com",
                Instant.now().minus(181, ChronoUnit.DAYS));
        UUID profileId = persistPregnancyProfile(userId);
        UUID consentId = persistConsentRecord(userId);
        em.flush();
        em.clear();

        int purged = erasureService.purgeExpiredAccounts(180);

        assertThat(purged).isEqualTo(1);
        // user row must be gone
        assertThat(users.findById(userId)).isEmpty();
        // FK-child rows must be gone (proves children-first ordering)
        assertThat(profiles.findById(profileId)).isEmpty();
        assertThat(consentRecords.findById(consentId)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Active users are untouched
    // -------------------------------------------------------------------------

    @Test
    void purgeExpiredAccounts_doesNotPurgeActiveUsers() {
        UUID userId = persistActiveUser("active@example.com");
        em.flush();
        em.clear();

        int purged = erasureService.purgeExpiredAccounts(180);

        assertThat(purged).isEqualTo(0);
        assertThat(users.findById(userId)).isPresent();
    }

    // -------------------------------------------------------------------------
    // Within-retention-window soft-deleted users are untouched
    // -------------------------------------------------------------------------

    @Test
    void purgeExpiredAccounts_doesNotPurgeUsersWithinRetentionWindow() {
        // Soft-deleted 10 days ago — within the 180-day retention window
        UUID userId = persistSoftDeletedUser("recent-delete@example.com",
                Instant.now().minus(10, ChronoUnit.DAYS));
        em.flush();
        em.clear();

        int purged = erasureService.purgeExpiredAccounts(180);

        assertThat(purged).isEqualTo(0);
        assertThat(users.findById(userId)).isPresent();
    }

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    @Test
    void purgeExpiredAccounts_isIdempotent() {
        UUID userId = persistSoftDeletedUser("idempotent@example.com",
                Instant.now().minus(181, ChronoUnit.DAYS));
        em.flush();
        em.clear();

        int first  = erasureService.purgeExpiredAccounts(180);
        int second = erasureService.purgeExpiredAccounts(180);

        assertThat(first).isEqualTo(1);
        // Second run: user is already gone — should return 0, not throw
        assertThat(second).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Correct count across multiple eligible / ineligible users
    // -------------------------------------------------------------------------

    @Test
    void purgeExpiredAccounts_returnsCorrectCountForMixedEligibility() {
        // Two expired, one within-window, one active — only expired are purged
        persistSoftDeletedUser("expired-1@example.com", Instant.now().minus(200, ChronoUnit.DAYS));
        persistSoftDeletedUser("expired-2@example.com", Instant.now().minus(181, ChronoUnit.DAYS));
        persistSoftDeletedUser("within-window@example.com", Instant.now().minus(10, ChronoUnit.DAYS));
        persistActiveUser("still-active@example.com");
        em.flush();
        em.clear();

        int purged = erasureService.purgeExpiredAccounts(180);

        assertThat(purged).isEqualTo(2);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID persistSoftDeletedUser(String email, Instant deletedAt) {
        User u = new User();
        u.setEmail(email);
        u.setStatus("deleted");
        u.setDeletedAt(deletedAt);
        return em.persistAndFlush(u).getId();
    }

    private UUID persistActiveUser(String email) {
        User u = new User();
        u.setEmail(email);
        return em.persistAndFlush(u).getId();
    }

    private UUID persistPregnancyProfile(UUID userId) {
        PregnancyProfile p = new PregnancyProfile();
        p.setUserId(userId);
        p.setEdd(LocalDate.of(2027, 6, 1));
        p.setEddBasis("due_date");
        return em.persistAndFlush(p).getId();
    }

    private UUID persistConsentRecord(UUID userId) {
        ConsentRecord c = new ConsentRecord();
        c.setUserId(userId);
        c.setConsentType("general_health");
        c.setGranted(true);
        c.setConsentTextVersion("v1.0-th");
        c.setLocale("th");
        return em.persistAndFlush(c).getId();
    }
}
