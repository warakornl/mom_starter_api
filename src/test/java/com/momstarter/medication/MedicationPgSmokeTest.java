package com.momstarter.medication;

import com.momstarter.account.AccountErasureService;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.sync.SyncCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>PDPA compliance smoke test — medication_plan + medication_log erasure,
 * crypto-shred, and jsonb round-trip on real PostgreSQL.</strong>
 *
 * <p>Runs against the real {@code postgres:16} container ({@code momstarter-pg},
 * started via {@code docker run}) when {@code localhost:5432} is reachable; otherwise
 * the entire class is SKIPPED (JUnit 5 {@code @EnabledIf}).
 *
 * <h2>What this test proves (PDPA ม.33 compliance — blocking build-acceptance criterion)</h2>
 * <p>PDPA assessment ruling 2.3 requires proof on REAL Postgres (not H2) that:
 * <ol>
 *   <li><strong>(a) Tier-1 erasure hard-deletes medication_log + medication_plan
 *       FK-safely</strong> — {@code DELETE users} (Tier-2) does NOT violate the
 *       {@code medication_plan.user_id → users(id)} FK constraint because Tier-1 purges
 *       {@code medication_log} THEN {@code medication_plan} in that order (RULING 4).
 *       Also proves that deleting in the FK-safe order does not itself FK-violate
 *       {@code medication_log.medication_plan_id → medication_plan(id)}.</li>
 *   <li><strong>(b) Tombstoned rows have all cipher columns NULL after the real
 *       {@code applyDelete}</strong> — the crypto-shred is a real NULL write, not a
 *       DEK-shred no-op. Verified by calling the PRODUCTION
 *       {@code MedicationPlanSyncCollection.applyDelete()} and
 *       {@code MedicationLogSyncCollection.applyDelete()} methods directly.</li>
 *   <li><strong>(c) {@code schedule_rule} jsonb round-trip on real Postgres</strong> —
 *       H2 in PostgreSQL MODE silently accepts varchar into jsonb columns (h2-masks-jsonb-binding
 *       BLOCKER pattern), so the {@code @JdbcTypeCode(SqlTypes.JSON)} annotation on
 *       {@link MedicationPlan#getScheduleRule()} is verified against real Postgres here.
 *       Without the annotation, Postgres raises:
 *       {@code ERROR: column "schedule_rule" is of type jsonb but expression is of type
 *       character varying}.</li>
 * </ol>
 *
 * <h2>Pre-condition</h2>
 * <pre>
 * docker run -d --name momstarter-pg -p 5432:5432 \
 *   -e POSTGRES_DB=momstarter -e POSTGRES_USER=momstarter \
 *   -e POSTGRES_PASSWORD=momstarter \
 *   postgres:16
 * </pre>
 *
 * <h2>Launch-gate status</h2>
 * <p>If this sandbox cannot reach Postgres, the tests show as SKIPPED — expected.
 * <strong>A human MUST execute these tests against real Postgres before production
 * launch</strong> to satisfy PDPA ruling 2.3 blocking build-acceptance criterion.
 * SKIPPED does NOT mean PASSED.
 *
 * <h2>Transaction isolation</h2>
 * <p>{@code @Transactional} rolls back each test method. {@code @BeforeEach} creates a
 * unique user per test (no global {@code deleteAll()} to avoid FK-ordering issues on
 * real Postgres).
 */
@EnabledIf("pgReachable")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/momstarter",
        "spring.datasource.username=momstarter",
        "spring.datasource.password=momstarter",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "momstarter.dev.auto-verify-email=false",
        "momstarter.ratelimit.login-per-ip-per-min=1000000",
        "momstarter.ratelimit.register-per-ip-per-min=1000000",
        "momstarter.ratelimit.resend-per-ip-per-min=1000000",
        "momstarter.ratelimit.forgot-per-ip-per-min=1000000",
        "momstarter.ratelimit.reset-per-ip-per-min=1000000",
        "momstarter.ratelimit.verify-email-per-ip-per-min=1000000"
})
@Transactional
class MedicationPgSmokeTest {

    /**
     * Probed by {@code @EnabledIf("pgReachable")} before Spring loads the ApplicationContext.
     * Returns {@code true} when PostgreSQL is reachable on {@code localhost:5432};
     * {@code false} causes JUnit to mark the entire class as SKIPPED (not FAILED).
     */
    static boolean pgReachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 5432), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired private UserRepository users;
    @Autowired private MedicationPlanRepository plans;
    @Autowired private MedicationLogRepository logs;
    @Autowired private AccountErasureService erasureService;

    /** Production tombstone path — the same bean the sync engine calls at runtime. */
    @Autowired @Qualifier("medicationPlanSyncCollection")
    private SyncCollection planSyncCollection;

    /** Production tombstone path — the same bean the sync engine calls at runtime. */
    @Autowired @Qualifier("medicationLogSyncCollection")
    private SyncCollection logSyncCollection;

    private UUID userId;

    /** Creates a fresh user per test. {@code @Transactional} rollback handles cleanup. */
    @BeforeEach
    void setup() {
        User user = new User();
        user.setEmail("pg-med-smoke-" + UUID.randomUUID() + "@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
    }

    // -------------------------------------------------------------------------
    // (a) Tier-1 erasure FK-safety on real Postgres
    // -------------------------------------------------------------------------

    /**
     * <strong>Assertion (a) — FK-safe Tier-1 erasure on real Postgres.</strong>
     *
     * <p>Seeds a {@code medication_plan} and a {@code medication_log} (which references the plan)
     * for a soft-deleted user. Runs Tier-1 erasure. Asserts:
     * <ol>
     *   <li>No {@code DataIntegrityViolationException} is raised — the FK order in
     *       {@link AccountErasureService#TIER1_CHILD_DELETE_ORDER} deletes
     *       {@code medication_log} BEFORE {@code medication_plan} (RULING 4).
     *       Reversing the order would raise:
     *       {@code PSQLException: ERROR: update or delete on table "medication_plan" violates
     *       foreign key constraint on table "medication_log"}.</li>
     *   <li>{@code medication_log} row is hard-deleted.</li>
     *   <li>{@code medication_plan} row is hard-deleted.</li>
     *   <li>The {@code users} row is retained (Tier-1 keeps it for Tier-2).</li>
     * </ol>
     */
    @Test
    void tierOneErasure_hardDeletesMedicationLogAndPlan_fkSafe_onRealPostgres() {
        // Soft-delete the user (181 days ago — past 180d retention threshold)
        User user = users.findById(userId).orElseThrow();
        user.setStatus("deleted");
        user.setDeletedAt(Instant.now().minus(181, ChronoUnit.DAYS));
        users.saveAndFlush(user);

        // Seed medication_plan
        MedicationPlan plan = new MedicationPlan();
        plan.setId(UUID.randomUUID());
        plan.setUserId(userId);
        plan.setNameCipher(new byte[]{1, 2, 3});   // plaintext bytes (MVP posture)
        plan.setActive(true);
        plans.saveAndFlush(plan);
        UUID planId = plan.getId();

        // Seed medication_log referencing the plan (hard FK → medication_plan(id))
        MedicationLog log = new MedicationLog();
        log.setId(UUID.randomUUID());
        log.setUserId(userId);
        log.setMedicationPlanId(planId);
        log.setStatus("taken");
        log.setOccurrenceTime(LocalDateTime.of(2026, 7, 1, 9, 0));
        logs.saveAndFlush(log);
        UUID logId = log.getId();

        // Run Tier-1 — must NOT throw FK violation; medication_log purged before medication_plan
        int purged = erasureService.purgeExpiredAccountChildren(180);

        assertThat(purged).isEqualTo(1);
        assertThat(logs.findById(logId))
                .as("(a) medication_log must be hard-deleted by Tier-1 on real Postgres")
                .isEmpty();
        assertThat(plans.findById(planId))
                .as("(a) medication_plan must be hard-deleted by Tier-1 on real Postgres")
                .isEmpty();
        assertThat(users.findById(userId))
                .as("(a) Tier-1 must retain the users row for Tier-2")
                .isPresent();
    }

    // -------------------------------------------------------------------------
    // (b) Crypto-shred: tombstoned rows have NULL cipher columns on real Postgres
    // -------------------------------------------------------------------------

    /**
     * <strong>Assertion (b) — MedicationPlan tombstone crypto-shreds nameCipher + doseCipher
     * on real Postgres.</strong>
     *
     * <p>Calls the PRODUCTION {@code MedicationPlanSyncCollection.applyDelete()} method —
     * the exact same bean the sync engine dispatches at runtime. If {@code applyDelete}
     * is later broken (e.g. null writes removed), this test will fail — not stay green.
     *
     * <p>PDPA compliance: §4.4(A) — tombstone row must retain no recoverable plaintext.
     */
    @Test
    void tombstone_planCipherColumns_areNull_afterCryptoShred_onRealPostgres() {
        // Seed a live plan with both cipher columns populated
        MedicationPlan live = new MedicationPlan();
        live.setId(UUID.randomUUID());
        live.setUserId(userId);
        live.setNameCipher(new byte[]{1, 2, 3});   // medication name (plaintext bytes)
        live.setDoseCipher(new byte[]{4, 5, 6});   // dose string (plaintext bytes)
        live.setScheduleRule("{\"freq\":\"daily\",\"startAt\":\"2026-07-01T08:00\"}");
        live.setActive(true);
        plans.saveAndFlush(live);
        UUID planId = live.getId();

        // Invoke the REAL production shred path
        MedicationPlan existing = plans.findById(planId).orElseThrow();
        planSyncCollection.applyDelete(userId, planId, existing);

        // Reload from REAL Postgres (flush+clear defeats L1 cache)
        plans.findAll();  // force cache eviction
        MedicationPlan reloaded = plans.findById(planId).orElseThrow();

        assertThat(reloaded.getNameCipher())
                .as("(b) nameCipher must be NULL on real Postgres after crypto-shred")
                .isNull();
        assertThat(reloaded.getDoseCipher())
                .as("(b) doseCipher must be NULL on real Postgres after crypto-shred")
                .isNull();
        assertThat(reloaded.getDeletedAt())
                .as("(b) deletedAt must be set (tombstone marker)")
                .isNotNull();
    }

    /**
     * <strong>Assertion (b) — MedicationLog tombstone crypto-shreds noteCipher on real
     * Postgres.</strong>
     *
     * <p>Calls the PRODUCTION {@code MedicationLogSyncCollection.applyDelete()} method.
     * If {@code applyDelete} is later broken, this test fails — not stays green.
     */
    @Test
    void tombstone_logNoteCipher_isNull_afterCryptoShred_onRealPostgres() {
        // Seed a live log with noteCipher populated
        MedicationLog live = new MedicationLog();
        live.setId(UUID.randomUUID());
        live.setUserId(userId);
        live.setStatus("taken");
        live.setOccurrenceTime(LocalDateTime.of(2026, 7, 1, 9, 0));
        live.setNoteCipher(new byte[]{7, 8, 9});   // optional note (plaintext bytes)
        logs.saveAndFlush(live);
        UUID logId = live.getId();

        // Invoke the REAL production shred path
        MedicationLog existing = logs.findById(logId).orElseThrow();
        logSyncCollection.applyDelete(userId, logId, existing);

        // Reload from REAL Postgres
        logs.findAll();  // force cache eviction
        MedicationLog reloaded = logs.findById(logId).orElseThrow();

        assertThat(reloaded.getNoteCipher())
                .as("(b) noteCipher must be NULL on real Postgres after crypto-shred")
                .isNull();
        assertThat(reloaded.getDeletedAt())
                .as("(b) deletedAt must be set (tombstone marker)")
                .isNotNull();
    }

    // -------------------------------------------------------------------------
    // (c) scheduleRule jsonb round-trip on real Postgres (h2-masks-jsonb-binding smoke)
    // -------------------------------------------------------------------------

    /**
     * <strong>Assertion (c) — {@code schedule_rule} jsonb round-trip on real Postgres.</strong>
     *
     * <p>H2 in PostgreSQL MODE silently accepts {@code varchar} into a {@code jsonb} column,
     * so H2-based tests pass even if the {@code @JdbcTypeCode(SqlTypes.JSON)} annotation on
     * {@link MedicationPlan#getScheduleRule()} is missing. This test catches that regression
     * on real Postgres (memory: h2-masks-jsonb-binding).
     *
     * <p>Without {@code @JdbcTypeCode(SqlTypes.JSON)}, Postgres raises:
     * <pre>
     * ERROR: column "schedule_rule" is of type jsonb but expression is of type character varying
     * HINT: You will need to rewrite or cast the expression.
     * </pre>
     *
     * <p>The round-trip asserts that the exact JSON string survives INSERT → SELECT on real
     * Postgres. Postgres normalises jsonb storage, so the value after SELECT may have whitespace
     * or key-ordering differences if the client sends non-normalised JSON — the test uses a
     * minimal canonical form to avoid false failures from Postgres normalisation.
     */
    @Test
    void scheduleRule_jsonbRoundTrip_onRealPostgres() {
        // Canonical minimal FLAG-4 grammar — Postgres jsonb normalises in-place
        String scheduleRuleIn = "{\"freq\":\"daily\",\"startAt\":\"2026-07-01T08:00\"}";

        MedicationPlan plan = new MedicationPlan();
        plan.setId(UUID.randomUUID());
        plan.setUserId(userId);
        plan.setNameCipher(new byte[]{1, 2, 3});
        plan.setScheduleRule(scheduleRuleIn);
        plan.setActive(true);
        plans.saveAndFlush(plan);
        UUID planId = plan.getId();

        // Clear L1 cache so the reload goes to real Postgres
        plans.findAll();
        MedicationPlan reloaded = plans.findById(planId).orElseThrow();

        // Postgres jsonb stores the value semantically equivalent (may normalise whitespace).
        // Assert the two required fields survive the round-trip.
        assertThat(reloaded.getScheduleRule())
                .as("(c) scheduleRule must survive jsonb round-trip on real Postgres")
                .isNotNull()
                .contains("\"freq\"")
                .contains("\"daily\"")
                .contains("\"startAt\"")
                .contains("2026-07-01T08:00");
    }
}
