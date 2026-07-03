package com.momstarter.sync;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.kickcount.KickCountSession;
import com.momstarter.kickcount.KickCountSessionRepository;
import com.momstarter.medication.MedicationLog;
import com.momstarter.medication.MedicationLogRepository;
import com.momstarter.medication.MedicationPlan;
import com.momstarter.medication.MedicationPlanRepository;
import com.momstarter.pregnancy.PregnancyProfile;
import com.momstarter.pregnancy.PregnancyProfileRepository;
import com.momstarter.selflog.SelfLog;
import com.momstarter.selflog.SelfLogRepository;
import com.momstarter.supply.SupplyItem;
import com.momstarter.supply.SupplyItemRepository;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TombstoneGcService}.
 *
 * <p>Verifies the central tombstone GC sweep list (database-reviewer follow-up #3):
 * {@code kick_count_session} is EXPLICITLY ENUMERATED — not just commented — alongside
 * every other sync collection that produces tombstones.
 *
 * <p>Flush/clear discipline: every test flushes the JPA session before running the GC
 * (so the JDBC DELETE can see the row) and then clears the L1 cache afterwards (so
 * that the findById check reads from the DB, not from the stale JPA session state).
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>{@code kick_count_session} tombstones older than TTL are purged.</li>
 *   <li>{@code supply_items} tombstones older than TTL are purged.</li>
 *   <li>Live rows (deletedAt IS NULL) are NOT touched regardless of age.</li>
 *   <li>Recent tombstones (within TTL) are NOT purged.</li>
 *   <li>{@link TombstoneGcService#purgeTableNames()} enumerates {@code kick_count_session}
 *       in the returned table list (the "central GC sweep list" the database-reviewer required).</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Import(TombstoneGcService.class)
class TombstoneGcServiceTest {

    @Autowired private TestEntityManager em;
    @Autowired private TombstoneGcService gcService;
    @Autowired private KickCountSessionRepository kickSessions;
    @Autowired private SelfLogRepository selfLogRepo;
    @Autowired private MedicationPlanRepository medicationPlanRepo;
    @Autowired private MedicationLogRepository medicationLogRepo;
    @Autowired private SupplyItemRepository supplyItems;
    @Autowired private PregnancyProfileRepository profiles;
    @Autowired private UserRepository users;

    // -------------------------------------------------------------------------
    // kick_count_session is in the enumerated purge-table list
    // -------------------------------------------------------------------------

    @Test
    void purgeTableNames_enumeratesKickCountSession() {
        // database-reviewer follow-up #3: kick_count_session MUST be truly enumerated
        // in the central GC sweep list — not just a comment
        List<String> tables = gcService.purgeTableNames();
        assertThat(tables).contains("kick_count_session");
    }

    @Test
    void purgeTableNames_enumeratesAllSyncTables() {
        // All tables with tombstone columns must be in the list.
        // pregnancy_profile was added in Phase 3 (hard-erasure prod-gate) — EDD/birth_date
        // are the most sensitive fields (PDPA ม.26) and must be subject to GC.
        // expenses was added in the expenses slice (non-health, offline-sync collection).
        List<String> tables = gcService.purgeTableNames();
        assertThat(tables).containsExactlyInAnyOrder(
                "supply_items",
                "reminders",
                "reminder_occurrences",
                "checklist_items",
                "kick_count_session",
                "pregnancy_profile",
                "expenses",
                "self_log",          // F2 fix: self_log tombstones must be hard-purged (PDPA ม.33)
                "medication_log",    // Task 5: SD-2 health data — note_cipher shredded on tombstone
                "medication_plan"    // Task 5: SD-2 health data — name/dose ciphers shredded on tombstone
        );
    }

    // -------------------------------------------------------------------------
    // pregnancy_profile: added to PURGE_TABLES in Phase 3 (blocker B)
    // -------------------------------------------------------------------------

    @Test
    void purge_pregnancyProfile_oldTombstone_purged() {
        User user = savedUser("gc-pp-1@example.com");
        PregnancyProfile p = buildPregnancyProfile(user.getId());
        // Tombstoned 181 days ago — past the 180-day TTL
        p.setDeletedAt(Instant.now().minus(181, ChronoUnit.DAYS));
        em.persistAndFlush(p);
        em.clear();

        int purged = gcService.purgeExpiredTombstones(180);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(profiles.findById(p.getId())).isEmpty();
    }

    @Test
    void purge_pregnancyProfile_recentTombstone_retained() {
        User user = savedUser("gc-pp-2@example.com");
        PregnancyProfile p = buildPregnancyProfile(user.getId());
        // Tombstoned 10 days ago — within TTL
        p.setDeletedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        em.persistAndFlush(p);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(profiles.findById(p.getId())).isPresent();
    }

    @Test
    void purge_pregnancyProfile_liveRow_notTouched() {
        User user = savedUser("gc-pp-3@example.com");
        PregnancyProfile p = buildPregnancyProfile(user.getId());
        // deletedAt = null — live row must not be touched
        em.persistAndFlush(p);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(profiles.findById(p.getId())).isPresent();
    }

    // -------------------------------------------------------------------------
    // kick_count_session: old tombstone purged
    // -------------------------------------------------------------------------

    @Test
    void purge_kickCountSession_oldTombstone_purged() {
        User user = savedUser("gc-kc-1@example.com");
        KickCountSession s = buildKickSession(user.getId());
        // Tombstoned 181 days ago (beyond 180-day TTL)
        s.setDeletedAt(Instant.now().minus(181, ChronoUnit.DAYS));
        em.persistAndFlush(s);
        // Clear the L1 cache so the GC's JDBC DELETE and the subsequent findById
        // both go to the actual DB rather than the JPA session state.
        em.clear();

        int purged = gcService.purgeExpiredTombstones(180);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        // Cache was cleared above — findById now reads from DB
        assertThat(kickSessions.findById(s.getId())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // kick_count_session: recent tombstone NOT purged
    // -------------------------------------------------------------------------

    @Test
    void purge_kickCountSession_recentTombstone_retained() {
        User user = savedUser("gc-kc-2@example.com");
        KickCountSession s = buildKickSession(user.getId());
        // Tombstoned 10 days ago (within TTL)
        s.setDeletedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        em.persistAndFlush(s);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(kickSessions.findById(s.getId())).isPresent();
    }

    // -------------------------------------------------------------------------
    // kick_count_session: live row NOT touched
    // -------------------------------------------------------------------------

    @Test
    void purge_kickCountSession_liveRow_notTouched() {
        User user = savedUser("gc-kc-3@example.com");
        KickCountSession s = buildKickSession(user.getId());
        // deletedAt = null → live
        em.persistAndFlush(s);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(kickSessions.findById(s.getId())).isPresent();
    }

    // -------------------------------------------------------------------------
    // supply_items: old tombstone purged (existing collection regression)
    // -------------------------------------------------------------------------

    @Test
    void purge_supplyItems_oldTombstone_purged() {
        User user = savedUser("gc-supply-1@example.com");
        SupplyItem item = new SupplyItem();
        item.setId(UUID.randomUUID());
        item.setUserId(user.getId());
        item.setName("test");
        item.setCategory("other");
        item.setDeletedAt(Instant.now().minus(200, ChronoUnit.DAYS));
        em.persistAndFlush(item);
        em.clear();

        int purged = gcService.purgeExpiredTombstones(180);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(supplyItems.findById(item.getId())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // expenses: old tombstone purged
    // -------------------------------------------------------------------------

    @Test
    void purge_expenses_oldTombstone_purged() {
        User user = savedUser("gc-expense-1@example.com");
        com.momstarter.expense.Expense e = new com.momstarter.expense.Expense();
        e.setId(UUID.randomUUID());
        e.setUserId(user.getId());
        e.setAmount(59000);
        e.setCategory("healthcare");
        e.setIncurredOn(LocalDate.of(2026, 7, 1));
        e.setDeletedAt(Instant.now().minus(200, ChronoUnit.DAYS));
        em.persistAndFlush(e);
        em.clear();

        int purged = gcService.purgeExpiredTombstones(180);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        // The expense tombstone must be hard-purged
        assertThat(em.find(com.momstarter.expense.Expense.class, e.getId())).isNull();
    }

    @Test
    void purge_expenses_recentTombstone_retained() {
        User user = savedUser("gc-expense-2@example.com");
        com.momstarter.expense.Expense e = new com.momstarter.expense.Expense();
        e.setId(UUID.randomUUID());
        e.setUserId(user.getId());
        e.setAmount(25000);
        e.setCategory("mother");
        e.setIncurredOn(LocalDate.of(2026, 7, 1));
        e.setDeletedAt(Instant.now().minus(10, ChronoUnit.DAYS)); // within 180-day TTL
        em.persistAndFlush(e);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(em.find(com.momstarter.expense.Expense.class, e.getId())).isNotNull();
    }

    @Test
    void purge_expenses_liveRow_notTouched() {
        User user = savedUser("gc-expense-3@example.com");
        com.momstarter.expense.Expense e = new com.momstarter.expense.Expense();
        e.setId(UUID.randomUUID());
        e.setUserId(user.getId());
        e.setAmount(10000);
        e.setCategory("other");
        e.setIncurredOn(LocalDate.of(2026, 7, 1));
        // deletedAt = null — live row
        em.persistAndFlush(e);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(em.find(com.momstarter.expense.Expense.class, e.getId())).isNotNull();
    }

    // -------------------------------------------------------------------------
    // self_log: F2 fix — tombstones must be hard-purged (PDPA ม.33 / consent withdrawal)
    // -------------------------------------------------------------------------

    /**
     * self_log tombstone older than 180-day TTL must be hard-purged (F2 / PDPA ม.33).
     *
     * <p>This was the F2 gap: self_log tombstones accumulated indefinitely because
     * {@code self_log} was absent from {@link TombstoneGcService#PURGE_TABLES}.
     * The fix adds it so tombstoned rows — including consent-withdrawal tombstones —
     * are hard-purged after the 180-day retention window.
     */
    @Test
    void purge_selfLog_oldTombstone_purged() {
        User user = savedUser("gc-sl-1@example.com");
        SelfLog s = buildSelfLog(user.getId(), "weight");
        // Tombstoned 181 days ago — past the 180-day TTL; must be hard-purged
        s.setValueNumeric(null);  // crypto-shredded on tombstone (PDPA §4.4(A))
        s.setDeletedAt(Instant.now().minus(181, ChronoUnit.DAYS));
        em.persistAndFlush(s);
        em.clear();

        int purged = gcService.purgeExpiredTombstones(180);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(selfLogRepo.findById(s.getId())).isEmpty();
    }

    /**
     * self_log tombstone within the 180-day retention window must NOT be purged.
     */
    @Test
    void purge_selfLog_recentTombstone_retained() {
        User user = savedUser("gc-sl-2@example.com");
        SelfLog s = buildSelfLog(user.getId(), "blood_pressure");
        // Tombstoned 10 days ago — within TTL; must be retained
        s.setValueNumeric(null);
        s.setDeletedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        em.persistAndFlush(s);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(selfLogRepo.findById(s.getId())).isPresent();
    }

    /**
     * Live (non-tombstoned) self_log rows must NEVER be touched by the GC sweep.
     */
    @Test
    void purge_selfLog_liveRow_notTouched() {
        User user = savedUser("gc-sl-3@example.com");
        SelfLog s = buildSelfLog(user.getId(), "swelling");
        // deletedAt = null — live row; must not be touched
        em.persistAndFlush(s);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(selfLogRepo.findById(s.getId())).isPresent();
    }

    // -------------------------------------------------------------------------
    // medication_plan: Task 5 — tombstones must be hard-purged (PDPA ม.33 / SD-2)
    // -------------------------------------------------------------------------

    /**
     * medication_plan tombstone older than 180-day TTL must be hard-purged (Task 5 / PDPA ม.33).
     *
     * <p>name_cipher and dose_cipher are crypto-shredded to null on tombstone (§4.4(A)).
     * The tombstone row itself must be purged after the 180-day TTL so the plan does not
     * accumulate indefinitely in the DB.
     */
    @Test
    void purge_medicationPlan_oldTombstone_purged() {
        User user = savedUser("gc-mp-1@example.com");
        MedicationPlan p = buildMedicationPlan(user.getId());
        // Tombstoned 181 days ago — past 180-day TTL; cipher columns already shredded
        p.setNameCipher(null);   // crypto-shredded (CHECK allows null when deleted_at IS NOT NULL)
        p.setDeletedAt(Instant.now().minus(181, ChronoUnit.DAYS));
        em.persistAndFlush(p);
        em.clear();

        int purged = gcService.purgeExpiredTombstones(180);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(medicationPlanRepo.findById(p.getId())).isEmpty();
    }

    /**
     * medication_plan tombstone within the 180-day retention window must NOT be purged.
     */
    @Test
    void purge_medicationPlan_recentTombstone_retained() {
        User user = savedUser("gc-mp-2@example.com");
        MedicationPlan p = buildMedicationPlan(user.getId());
        // Tombstoned 10 days ago — within TTL; must be retained
        p.setNameCipher(null);
        p.setDeletedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        em.persistAndFlush(p);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(medicationPlanRepo.findById(p.getId())).isPresent();
    }

    /**
     * Live (non-tombstoned) medication_plan rows must NEVER be touched by the GC sweep.
     */
    @Test
    void purge_medicationPlan_liveRow_notTouched() {
        User user = savedUser("gc-mp-3@example.com");
        MedicationPlan p = buildMedicationPlan(user.getId());
        // deletedAt = null — live; must not be touched
        em.persistAndFlush(p);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(medicationPlanRepo.findById(p.getId())).isPresent();
    }

    // -------------------------------------------------------------------------
    // medication_log: Task 5 — tombstones must be hard-purged (PDPA ม.33 / SD-2)
    // -------------------------------------------------------------------------

    /**
     * medication_log tombstone older than 180-day TTL must be hard-purged (Task 5 / PDPA ม.33).
     *
     * <p>note_cipher is crypto-shredded to null on tombstone (§4.4(A)).
     * The tombstone row itself must be purged after the 180-day TTL.
     */
    @Test
    void purge_medicationLog_oldTombstone_purged() {
        User user = savedUser("gc-ml-1@example.com");
        MedicationPlan plan = buildMedicationPlan(user.getId());
        em.persistAndFlush(plan);
        MedicationLog l = buildMedicationLog(user.getId(), plan.getId());
        // Tombstoned 181 days ago — past 180-day TTL
        l.setNoteCipher(null);   // crypto-shredded on tombstone
        l.setDeletedAt(Instant.now().minus(181, ChronoUnit.DAYS));
        em.persistAndFlush(l);
        em.clear();

        int purged = gcService.purgeExpiredTombstones(180);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(medicationLogRepo.findById(l.getId())).isEmpty();
    }

    /**
     * medication_log tombstone within the 180-day retention window must NOT be purged.
     */
    @Test
    void purge_medicationLog_recentTombstone_retained() {
        User user = savedUser("gc-ml-2@example.com");
        MedicationPlan plan = buildMedicationPlan(user.getId());
        em.persistAndFlush(plan);
        MedicationLog l = buildMedicationLog(user.getId(), plan.getId());
        // Tombstoned 10 days ago — within TTL; must be retained
        l.setNoteCipher(null);
        l.setDeletedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        em.persistAndFlush(l);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(medicationLogRepo.findById(l.getId())).isPresent();
    }

    /**
     * Live (non-tombstoned) medication_log rows must NEVER be touched by the GC sweep.
     */
    @Test
    void purge_medicationLog_liveRow_notTouched() {
        User user = savedUser("gc-ml-3@example.com");
        MedicationPlan plan = buildMedicationPlan(user.getId());
        em.persistAndFlush(plan);
        MedicationLog l = buildMedicationLog(user.getId(), plan.getId());
        // deletedAt = null — live; must not be touched
        em.persistAndFlush(l);
        em.clear();

        gcService.purgeExpiredTombstones(180);

        assertThat(medicationLogRepo.findById(l.getId())).isPresent();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User savedUser(String email) {
        User u = new User();
        u.setEmail(email);
        return em.persistAndFlush(u);
    }

    private PregnancyProfile buildPregnancyProfile(UUID userId) {
        PregnancyProfile p = new PregnancyProfile();
        p.setUserId(userId);
        p.setEdd(LocalDate.of(2027, 6, 1));
        p.setEddBasis("due_date");
        return p;
    }

    private KickCountSession buildKickSession(UUID userId) {
        KickCountSession s = new KickCountSession();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setStartedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        s.setEndedAt(LocalDateTime.of(2026, 7, 1, 9, 15));
        s.setDurationSeconds(900);
        s.setMovementCount(10);
        s.setTargetCount(10);
        s.setStatus("completed");
        return s;
    }

    private SelfLog buildSelfLog(UUID userId, String metricType) {
        SelfLog s = new SelfLog();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setMetricType(metricType);
        s.setLoggedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        s.setValueNumeric(new byte[]{1, 2, 3});  // non-null to pass empty_value guard
        return s;
    }

    private MedicationPlan buildMedicationPlan(UUID userId) {
        MedicationPlan p = new MedicationPlan();
        p.setId(UUID.randomUUID());
        p.setUserId(userId);
        p.setNameCipher(new byte[]{1, 2, 3});   // non-null: satisfies ck_medication_plan__live_name
        p.setActive(true);
        return p;
    }

    private MedicationLog buildMedicationLog(UUID userId, UUID planId) {
        MedicationLog l = new MedicationLog();
        l.setId(UUID.randomUUID());
        l.setUserId(userId);
        l.setMedicationPlanId(planId);
        l.setStatus("taken");
        l.setOccurrenceTime(LocalDateTime.of(2026, 7, 1, 9, 0));
        l.setNoteCipher(new byte[]{4, 5, 6});   // will be null-shredded on tombstone
        return l;
    }
}
