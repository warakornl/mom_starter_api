package com.momstarter.account;

import com.momstarter.auth.AuthIdentity;
import com.momstarter.auth.AuthIdentityRepository;
import com.momstarter.auth.EmailVerificationToken;
import com.momstarter.auth.EmailVerificationTokenRepository;
import com.momstarter.auth.PasswordResetToken;
import com.momstarter.auth.PasswordResetTokenRepository;
import com.momstarter.auth.RefreshToken;
import com.momstarter.auth.RefreshTokenRepository;
import com.momstarter.account.AccountDek;
import com.momstarter.account.AccountDekRepository;
import com.momstarter.checklist.ChecklistItem;
import com.momstarter.checklist.ChecklistItemRepository;
import com.momstarter.consent.ConsentRecord;
import com.momstarter.consent.ConsentRecordRepository;
import com.momstarter.kickcount.KickCountSession;
import com.momstarter.kickcount.KickCountSessionRepository;
import com.momstarter.pregnancy.PregnancyProfile;
import com.momstarter.pregnancy.PregnancyProfileRepository;
import com.momstarter.reminder.Reminder;
import com.momstarter.reminder.ReminderOccurrence;
import com.momstarter.reminder.ReminderOccurrenceRepository;
import com.momstarter.reminder.ReminderRepository;
import com.momstarter.expense.Expense;
import com.momstarter.expense.ExpenseRepository;
import com.momstarter.medication.MedicationLog;
import com.momstarter.medication.MedicationLogRepository;
import com.momstarter.medication.MedicationPlan;
import com.momstarter.medication.MedicationPlanRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AccountErasureService} — two-tier erasure model.
 *
 * <h2>Two-tier model</h2>
 * <ul>
 *   <li><strong>Tier 1 (180d)</strong>: {@link AccountErasureService#purgeExpiredAccountChildren}
 *       hard-purges health/auth child rows (10 tables) but KEEPS the {@code users} row and
 *       the {@code consent_record} rows. The {@code users} row is the FK anchor that
 *       {@code consent_record} references; discarding it at 180d would violate the FK and
 *       destroy PDPA ม.37 consent-audit evidence prematurely.</li>
 *   <li><strong>Tier 2 (legal-hold, ~1yr)</strong>: {@link AccountErasureService#purgeLegalHoldAccounts}
 *       hard-purges {@code consent_record} THEN {@code users} (FK-safe order) for accounts
 *       soft-deleted longer than the legal-hold window (default 365d, LEGAL-PENDING).</li>
 * </ul>
 *
 * <h2>FK-safety proof</h2>
 * <p>H2 in PostgreSQL mode enforces {@code ON DELETE RESTRICT} exactly as PostgreSQL does.
 * If any test method invokes a deletion in wrong FK order, H2 raises
 * {@code DataIntegrityViolationException} and the test fails — proving the order is safe.
 *
 * <h2>Strengthened cascade coverage</h2>
 * <p>The Tier-1 cascade test seeds a row in EVERY Tier-1 child table (all 11, incl. self_log). If any
 * table is accidentally omitted from {@code TIER1_CHILD_DELETE_ORDER}, its row survives
 * after the purge and the corresponding {@code findById} assertion fails — protecting the
 * codebase against future tables (e.g. {@code report_audit}) being forgotten.
 *
 * <h2>H2 / jsonb note</h2>
 * <p>Plain JDBC {@code DELETE WHERE user_id = ?} is used in the service; no jsonb binding
 * occurs in the delete path. The {@code reminders.recurrence_rule} INSERT (in test setup)
 * goes through Hibernate with {@code @JdbcTypeCode(SqlTypes.JSON)}, which H2 in PostgreSQL
 * mode accepts. The h2-masks-jsonb-binding pitfall therefore does not affect these tests.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li><strong>Tier-1:</strong> all 11 child tables purged (incl. self_log); users + consent_record retained.</li>
 *   <li><strong>Tier-1:</strong> active users (deleted_at IS NULL) are not touched.</li>
 *   <li><strong>Tier-1:</strong> recently-deleted users (within 180d) are not touched.</li>
 *   <li><strong>Tier-1:</strong> correct count across mixed eligibility.</li>
 *   <li><strong>Tier-2:</strong> consent_record THEN users deleted in FK-safe order.</li>
 *   <li><strong>Tier-2:</strong> users within legal-hold window are not touched.</li>
 *   <li><strong>Tier-2:</strong> idempotent — second run returns 0.</li>
 *   <li><strong>Tier-2:</strong> active users (deleted_at IS NULL) are not touched.</li>
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

    // Repositories for existence checks after purge
    @Autowired private UserRepository users;
    @Autowired private ConsentRecordRepository consentRecords;
    @Autowired private PregnancyProfileRepository profiles;
    @Autowired private AuthIdentityRepository authIdentities;
    @Autowired private PasswordResetTokenRepository passwordResetTokens;
    @Autowired private EmailVerificationTokenRepository emailVerificationTokens;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private ExpenseRepository expenseItems;
    @Autowired private SelfLogRepository selfLogRepo;
    @Autowired private SupplyItemRepository supplyItems;
    @Autowired private ReminderRepository reminders;
    @Autowired private ReminderOccurrenceRepository reminderOccurrences;
    @Autowired private ChecklistItemRepository checklistItems;
    @Autowired private KickCountSessionRepository kickSessions;
    @Autowired private MedicationPlanRepository medicationPlans;
    @Autowired private MedicationLogRepository medicationLogs;
    @Autowired private AccountDekRepository accountDekRepository;

    // =========================================================================
    // TIER-1: purgeExpiredAccountChildren
    // =========================================================================

    /**
     * Tier-1 strengthened cascade test: seeds a row in EVERY Tier-1 child table (all 15,
     * including account_dek, self_log, medication_log, medication_plan) plus a
     * {@code consent_record}. Verifies that all 15 child-table rows are purged while the
     * {@code users} row and {@code consent_record} row are RETAINED.
     *
     * <p>If any table is missing from {@code TIER1_CHILD_DELETE_ORDER}, its row survives
     * and the corresponding {@code findById} assertion fails. FK violations on incorrect
     * deletion order also manifest as a {@code DataIntegrityViolationException}.
     *
     * <p>FK-critical: {@code account_dek} (first entry — belt-and-suspenders crypto-shred
     * backstop; ADR IMPORTANT-1). Omission would cause the Tier-2 {@code DELETE FROM users}
     * to FK-violate if a row survived T0 (same failure class as self_log F1 fix).
     *
     * <p>FK-critical order for medication tables (RULING 4):
     * {@code medication_log} MUST appear BEFORE {@code medication_plan} in
     * {@link AccountErasureService#TIER1_CHILD_DELETE_ORDER} because
     * {@code medication_log.medication_plan_id REFERENCES medication_plan(id)}.
     * Reversing the order raises an FK violation and breaks account erasure.
     *
     * <p>TDD (sub-slice c): {@code account_dek} row seeded and assertion added BEFORE
     * {@code "account_dek"} was added to {@code TIER1_CHILD_DELETE_ORDER}. The test
     * fails until that entry is present.
     */
    @Test
    void purgeExpiredAccountChildren_tier1_cascadesAllChildTablesAndKeepsUserAndConsentRecord() {
        UUID userId = persistSoftDeletedUser("tier1-cascade@example.com",
                Instant.now().minus(181, ChronoUnit.DAYS));

        // Seed every Tier-1 child table (15 tables, incl. account_dek, self_log, medication_*)
        UUID dekId          = persistAccountDek(userId);   // crypto-shred backstop (sub-slice c)
        UUID profileId      = persistPregnancyProfile(userId);
        UUID authId         = persistAuthIdentity(userId);
        UUID pwdTokenId     = persistPasswordResetToken(userId);
        UUID emailTokenId   = persistEmailVerificationToken(userId);
        UUID refreshId      = persistRefreshToken(userId);
        UUID supplyId       = persistSupplyItem(userId);
        UUID expenseId      = persistExpense(userId);
        UUID reminderId     = persistReminder(userId);
        UUID occurrenceId   = persistReminderOccurrence(userId, reminderId);
        UUID checklistId    = persistChecklistItem(userId);
        UUID kickId         = persistKickCountSession(userId);
        UUID selfLogId      = persistSelfLog(userId);      // F1: was missing, caused FK break
        UUID planId         = persistMedicationPlan(userId);
        // medication_log FK → medication_plan; must be deleted before the plan in Tier-1
        UUID medLogId       = persistMedicationLog(userId, planId);

        // Also persist a consent_record — it must NOT be deleted by Tier-1
        UUID consentId = persistConsentRecord(userId);

        em.flush();
        em.clear();

        int processed = erasureService.purgeExpiredAccountChildren(180);

        assertThat(processed).isEqualTo(1);

        // account_dek — Tier-1 FK backstop (belt-and-suspenders for T0 crypto-shred)
        assertThat(accountDekRepository.findById(dekId))
                .as("account_dek (crypto-shred backstop) — must be purged by Tier-1 to avoid "
                        + "FK violation on Tier-2 DELETE FROM users (ADR IMPORTANT-1)")
                .isEmpty();

        // All other 14 Tier-1 child rows must be gone
        assertThat(profiles.findById(profileId)).isEmpty();
        assertThat(authIdentities.findById(authId)).isEmpty();
        assertThat(passwordResetTokens.findById(pwdTokenId)).isEmpty();
        assertThat(emailVerificationTokens.findById(emailTokenId)).isEmpty();
        assertThat(refreshTokens.findById(refreshId)).isEmpty();
        assertThat(supplyItems.findById(supplyId)).isEmpty();
        assertThat(expenseItems.findById(expenseId)).isEmpty();
        assertThat(reminders.findById(reminderId)).isEmpty();
        assertThat(reminderOccurrences.findById(occurrenceId)).isEmpty();
        assertThat(checklistItems.findById(checklistId)).isEmpty();
        assertThat(kickSessions.findById(kickId)).isEmpty();
        assertThat(selfLogRepo.findById(selfLogId))
                .as("self_log (F1) — must be purged by Tier-1 to avoid FK break on DELETE users")
                .isEmpty();
        assertThat(medicationLogs.findById(medLogId))
                .as("medication_log — must be purged BEFORE medication_plan (FK constraint)")
                .isEmpty();
        assertThat(medicationPlans.findById(planId))
                .as("medication_plan — must be purged by Tier-1 (RULING 4 / FK → users)")
                .isEmpty();

        // users row MUST be retained — it is the FK anchor until Tier-2 runs
        assertThat(users.findById(userId))
                .as("Tier-1 must NOT delete the users row")
                .isPresent();

        // consent_record MUST be retained — PDPA ม.37 audit evidence for legal-hold window
        assertThat(consentRecords.findById(consentId))
                .as("Tier-1 must NOT delete consent_record rows (PDPA ม.37 legal-hold)")
                .isPresent();
    }

    /**
     * Active users (deleted_at IS NULL) must never be touched by Tier-1.
     */
    @Test
    void purgeExpiredAccountChildren_tier1_doesNotTouchActiveUsers() {
        UUID userId = persistActiveUser("active-t1@example.com");
        em.flush();
        em.clear();

        int processed = erasureService.purgeExpiredAccountChildren(180);

        assertThat(processed).isEqualTo(0);
        assertThat(users.findById(userId)).isPresent();
    }

    /**
     * Users soft-deleted WITHIN the 180-day retention window must not be touched by Tier-1.
     */
    @Test
    void purgeExpiredAccountChildren_tier1_doesNotTouchUsersWithinRetentionWindow() {
        UUID userId = persistSoftDeletedUser("recent-t1@example.com",
                Instant.now().minus(10, ChronoUnit.DAYS));
        em.flush();
        em.clear();

        int processed = erasureService.purgeExpiredAccountChildren(180);

        assertThat(processed).isEqualTo(0);
        assertThat(users.findById(userId)).isPresent();
    }

    /**
     * Returns the count of eligible user accounts processed (found past the retention cutoff).
     * Mixed eligibility: 2 expired, 1 within-window, 1 active → expects 2.
     */
    @Test
    void purgeExpiredAccountChildren_tier1_returnsCorrectCountForMixedEligibility() {
        persistSoftDeletedUser("expired-t1-a@example.com", Instant.now().minus(200, ChronoUnit.DAYS));
        persistSoftDeletedUser("expired-t1-b@example.com", Instant.now().minus(181, ChronoUnit.DAYS));
        persistSoftDeletedUser("within-t1@example.com",    Instant.now().minus(10, ChronoUnit.DAYS));
        persistActiveUser("active-t1-b@example.com");
        em.flush();
        em.clear();

        int processed = erasureService.purgeExpiredAccountChildren(180);

        assertThat(processed).isEqualTo(2);
    }

    /**
     * Tier-1 FK-backstop test (sub-slice c, ADR IMPORTANT-1): a surviving {@code account_dek}
     * row — e.g., from a crash-interrupted T0 — is cleaned by Tier-1 so that the subsequent
     * Tier-2 {@code DELETE FROM users} does NOT FK-violate.
     *
     * <p>Simulates the exact failure scenario: account_dek row survives to the Tier-1 window
     * (T0 did not fire, or was interrupted), Tier-1 backstop deletes it, then Tier-2 can
     * safely delete the users row without a {@code DataIntegrityViolationException}.
     *
     * <p>TDD: test written BEFORE {@code "account_dek"} was added to
     * {@code TIER1_CHILD_DELETE_ORDER}. It fails until that entry is present.
     */
    @Test
    void purgeExpiredAccountChildren_tier1_accountDekBackstop_allowsTier2ToDeleteUsers() {
        UUID userId = persistSoftDeletedUser("dek-backstop@example.com",
                Instant.now().minus(366, ChronoUnit.DAYS)); // past both 180d and 365d thresholds
        UUID dekId = persistAccountDek(userId);
        UUID consentId = persistConsentRecord(userId);
        em.flush();
        em.clear();

        // Tier-1 must clean up account_dek (belt-and-suspenders backstop)
        int tier1Count = erasureService.purgeExpiredAccountChildren(180);
        assertThat(tier1Count).isEqualTo(1);

        em.clear();
        assertThat(accountDekRepository.findById(dekId))
                .as("Tier-1 backstop: account_dek must be gone before Tier-2 runs")
                .isEmpty();

        // Tier-2 must now succeed — no FK violation from a surviving account_dek row
        int tier2Count = erasureService.purgeLegalHoldAccounts(365);
        assertThat(tier2Count).isEqualTo(1);

        em.clear();
        assertThat(users.findById(userId))
                .as("Tier-2 must hard-delete the users row after Tier-1 cleared account_dek")
                .isEmpty();
    }

    // =========================================================================
    // TIER-2: purgeLegalHoldAccounts
    // =========================================================================

    /**
     * Tier-2 FK-safe cascade test: after Tier-1 has already cleaned health/auth children,
     * Tier-2 must delete {@code consent_record} BEFORE {@code users} (FK-safe).
     *
     * <p>If the service deletes {@code users} before {@code consent_record}, H2 raises
     * {@code DataIntegrityViolationException} (RESTRICT) and this test fails — proving
     * the correct FK order is enforced.
     */
    @Test
    void purgeLegalHoldAccounts_tier2_deletesConsentRecordThenUsers() {
        // Simulate state AFTER Tier-1 ran: only users + consent_record remain
        UUID userId = persistSoftDeletedUser("tier2-cascade@example.com",
                Instant.now().minus(366, ChronoUnit.DAYS));
        UUID consentId = persistConsentRecord(userId);
        em.flush();
        em.clear();

        int purged = erasureService.purgeLegalHoldAccounts(365);

        assertThat(purged).isEqualTo(1);
        // consent_record must be gone (purged by Tier-2)
        assertThat(consentRecords.findById(consentId))
                .as("Tier-2 must delete consent_record")
                .isEmpty();
        // users row must be gone (purged by Tier-2, after consent_record)
        assertThat(users.findById(userId))
                .as("Tier-2 must delete the users row")
                .isEmpty();
    }

    /**
     * Users soft-deleted WITHIN the legal-hold window must not be touched by Tier-2.
     */
    @Test
    void purgeLegalHoldAccounts_tier2_doesNotTouchUsersWithinLegalHoldWindow() {
        UUID userId = persistSoftDeletedUser("recent-t2@example.com",
                Instant.now().minus(10, ChronoUnit.DAYS));
        UUID consentId = persistConsentRecord(userId);
        em.flush();
        em.clear();

        int purged = erasureService.purgeLegalHoldAccounts(365);

        assertThat(purged).isEqualTo(0);
        assertThat(users.findById(userId)).isPresent();
        assertThat(consentRecords.findById(consentId)).isPresent();
    }

    /**
     * Tier-2 is idempotent: second run finds no eligible users (already hard-purged) → 0.
     */
    @Test
    void purgeLegalHoldAccounts_tier2_isIdempotent() {
        UUID userId = persistSoftDeletedUser("idempotent-t2@example.com",
                Instant.now().minus(366, ChronoUnit.DAYS));
        persistConsentRecord(userId);
        em.flush();
        em.clear();

        int first  = erasureService.purgeLegalHoldAccounts(365);
        int second = erasureService.purgeLegalHoldAccounts(365);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
    }

    /**
     * Active users (deleted_at IS NULL) must never be touched by Tier-2.
     */
    @Test
    void purgeLegalHoldAccounts_tier2_doesNotTouchActiveUsers() {
        UUID userId = persistActiveUser("active-t2@example.com");
        em.flush();
        em.clear();

        int purged = erasureService.purgeLegalHoldAccounts(365);

        assertThat(purged).isEqualTo(0);
        assertThat(users.findById(userId)).isPresent();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Persists an {@code account_dek} row for the given user (simulates a DEK that survived T0,
     * triggering the Tier-1 FK-backstop path in {@link AccountErasureService}).
     *
     * <p>The PK of {@code account_dek} is {@code user_id}, so the returned UUID is the same
     * as the input {@code userId} — used for the {@code findById} assertion after the purge.
     *
     * <p>ADR IMPORTANT-1 guard: if this row were NOT purged by Tier-1, the subsequent
     * {@code DELETE FROM users} in Tier-2 would FK-violate (same failure class as the
     * self_log F1 fix documented at lines 97-98).
     */
    private UUID persistAccountDek(UUID userId) {
        AccountDek dek = new AccountDek();
        dek.setUserId(userId);
        dek.setWrappedDek(new byte[]{1, 2, 3, 4});
        dek.setKmsKeyId("mock-cmk/test");
        dek.setWrapContext("accountId=" + userId);
        em.persistAndFlush(dek);
        return userId; // PK = user_id
    }

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

    private UUID persistAuthIdentity(UUID userId) {
        AuthIdentity a = new AuthIdentity();
        a.setUserId(userId);
        a.setProvider("google");
        a.setProviderSub("sub-" + UUID.randomUUID());
        a.setEmail("erasure-test@example.com");
        return em.persistAndFlush(a).getId();
    }

    private UUID persistPasswordResetToken(UUID userId) {
        PasswordResetToken t = new PasswordResetToken();
        t.setUserId(userId);
        t.setTokenHash("prt-hash-" + UUID.randomUUID());
        t.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        return em.persistAndFlush(t).getId();
    }

    private UUID persistEmailVerificationToken(UUID userId) {
        EmailVerificationToken t = new EmailVerificationToken();
        t.setUserId(userId);
        t.setTokenHash("evt-hash-" + UUID.randomUUID());
        t.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        return em.persistAndFlush(t).getId();
    }

    private UUID persistRefreshToken(UUID userId) {
        RefreshToken t = new RefreshToken();
        t.setUserId(userId);
        t.setTokenHash("rt-hash-" + UUID.randomUUID());
        t.setFamilyId(UUID.randomUUID());
        t.setDeviceId("device-erasure-test");
        t.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        return em.persistAndFlush(t).getId();
    }

    private UUID persistSupplyItem(UUID userId) {
        SupplyItem s = new SupplyItem();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setName("erasure-test-item");
        s.setCategory("other");
        return em.persistAndFlush(s).getId();
    }

    private UUID persistExpense(UUID userId) {
        Expense e = new Expense();
        e.setId(UUID.randomUUID());
        e.setUserId(userId);
        e.setAmount(59000);
        e.setCategory("healthcare");
        e.setIncurredOn(java.time.LocalDate.of(2026, 7, 1));
        return em.persistAndFlush(e).getId();
    }

    private UUID persistReminder(UUID userId) {
        Reminder r = new Reminder();
        r.setId(UUID.randomUUID());
        r.setUserId(userId);
        r.setType("custom");
        r.setDisplayTitle("Erasure test reminder");
        r.setRecurrenceRule("{\"freq\":\"one_off\"}");
        r.setStartAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        return em.persistAndFlush(r).getId();
    }

    private UUID persistReminderOccurrence(UUID userId, UUID reminderId) {
        ReminderOccurrence o = new ReminderOccurrence();
        o.setId(UUID.randomUUID());
        o.setUserId(userId);
        o.setReminderId(reminderId);
        o.setScheduledLocalTime(LocalDateTime.of(2026, 7, 1, 9, 0)); // minute-precision (SECOND=0)
        o.setStatus("done");
        o.setActedAt(Instant.now());
        return em.persistAndFlush(o).getId();
    }

    private UUID persistChecklistItem(UUID userId) {
        ChecklistItem c = new ChecklistItem();
        c.setId(UUID.randomUUID());
        c.setUserId(userId);
        c.setCategory("checklist_task");
        c.setTitle("Erasure test checklist item");
        return em.persistAndFlush(c).getId();
    }

    private UUID persistKickCountSession(UUID userId) {
        KickCountSession s = new KickCountSession();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setStartedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        s.setEndedAt(LocalDateTime.of(2026, 7, 1, 9, 15));
        s.setDurationSeconds(900);
        s.setMovementCount(10);
        s.setTargetCount(10);
        s.setStatus("completed");
        return em.persistAndFlush(s).getId();
    }

    /**
     * Persists a self_log row for the given user.
     *
     * <p>F1 guard: self_log has {@code user_id REFERENCES users(id) ON DELETE RESTRICT}.
     * A row here must be purged by Tier-1 BEFORE Tier-2 deletes the users row, or the
     * FK constraint causes erasure to fail with DataIntegrityViolationException.
     */
    private UUID persistSelfLog(UUID userId) {
        SelfLog s = new SelfLog();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setMetricType("weight");
        s.setLoggedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        s.setValueNumeric(new byte[]{1, 2, 3});  // non-null to satisfy empty_value guard
        return em.persistAndFlush(s).getId();
    }

    /**
     * Persists a medication_plan row for the given user.
     *
     * <p>FK-order guard: {@code medication_plan} has {@code user_id REFERENCES users(id) ON DELETE
     * RESTRICT}. Must be purged by Tier-1 AFTER {@code medication_log} (which has a hard FK into
     * this table) and BEFORE Tier-2 deletes the users row (RULING 4).
     */
    private UUID persistMedicationPlan(UUID userId) {
        MedicationPlan p = new MedicationPlan();
        p.setId(UUID.randomUUID());
        p.setUserId(userId);
        p.setNameCipher(new byte[]{1, 2, 3});   // non-null: satisfies ck_medication_plan__live_name
        p.setActive(true);
        return em.persistAndFlush(p).getId();
    }

    /**
     * Persists a medication_log row referencing the given plan.
     *
     * <p>FK-order guard: {@code medication_log.medication_plan_id REFERENCES medication_plan(id)}.
     * Must be purged by Tier-1 BEFORE {@code medication_plan} is deleted (RULING 4), or the
     * FK constraint causes erasure to fail with DataIntegrityViolationException.
     */
    private UUID persistMedicationLog(UUID userId, UUID medicationPlanId) {
        MedicationLog l = new MedicationLog();
        l.setId(UUID.randomUUID());
        l.setUserId(userId);
        l.setMedicationPlanId(medicationPlanId);
        l.setStatus("taken");
        l.setOccurrenceTime(LocalDateTime.of(2026, 7, 1, 9, 0));
        return em.persistAndFlush(l).getId();
    }
}
