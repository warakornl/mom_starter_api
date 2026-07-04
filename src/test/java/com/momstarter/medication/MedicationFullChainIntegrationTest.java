package com.momstarter.medication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.AccountErasureService;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.ConsentChecker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-layer integration tests for the Medication feature (Slice 2, Task 12 QA pass).
 *
 * <p>These tests are the cross-cutting gap-fillers identified in the traceability matrix.
 * Per-task TDD covered each hop independently; these tests thread the full pipeline in a
 * single call chain so that integration breaks surface immediately.
 *
 * <h2>Gaps closed</h2>
 * <ul>
 *   <li><strong>GAP-MED-CHAIN (full chain)</strong>: no existing test exercised the complete
 *       pipeline {@code POST /sync/push} (medicationPlans + medicationLogs with
 *       general_health granted) → persisted in DB → {@code GET /medication-plans} returns
 *       same plan → {@code GET /medication-logs} returns same log → {@code GET /account/export}
 *       includes both (PDPA ม.30/31 portability) → Tier-1 erasure deletes log then plan
 *       (FK-safe order, RULING 4 / ม.33). This test closes that gap.</li>
 *   <li><strong>GAP-MED-CONSENT-CHAIN (consent-denied variant)</strong>: the existing
 *       {@code MedicationPlanSyncMvcTest} and {@code MedicationLogSyncMvcTest} assert that a
 *       push is rejected when general_health is absent and that no DB row is persisted. But
 *       neither then calls {@code GET /medication-plans} or {@code GET /medication-logs} to
 *       confirm the list is empty (i.e., the rejected records genuinely did not leak through).
 *       This test closes that gap.</li>
 * </ul>
 *
 * <p>Both tests use real MVC / real H2 / real Flyway (same posture as
 * {@link MedicationPlanMvcTest} and {@link MedicationLogMvcTest}). ConsentChecker is mocked
 * to control the consent gate without a real consent_record row.
 *
 * <p>Spec refs:
 * <ul>
 *   <li>medication-behavior.md §A.1 (push), §A.2 (GET plans), §A.3 (GET logs), §A.4 (export)</li>
 *   <li>medication-encryption-and-schema.md RULING 4 (FK-safe erasure order: log → plan)</li>
 *   <li>pdpa-assessment.md ม.30 (portability), ม.33 (erasure)</li>
 *   <li>medication-behavior.md §4.4(A) (crypto-shred on tombstone)</li>
 *   <li>INV-M3: server never interprets name/dose ciphertext (name field opaque in GET response)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class MedicationFullChainIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private MedicationPlanRepository planRepo;
    @Autowired private MedicationLogRepository logRepo;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountErasureService erasureService;

    @PersistenceContext
    private EntityManager em;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    private static final byte[] NAME_BYTES =
            "Amoxicillin".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String NAME_B64 = Base64.getEncoder().encodeToString(NAME_BYTES);
    private static final String OCCURRENCE_TIME = "2026-07-04T09:00";

    @BeforeEach
    void setup() {
        logRepo.deleteAll();
        planRepo.deleteAll();
        users.deleteAll();

        user = new User();
        user.setEmail("med-chain@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);

        // Default: all consents granted
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // =========================================================================
    // GAP-MED-CHAIN — Full chain:
    //   push(plan + log) → persist → GET /medication-plans + /medication-logs
    //                              → GET /account/export
    //                              → Tier-1 erasure (FK-safe: log before plan)
    //
    // Spec: medication-behavior.md §A.1/§A.2/§A.3; pdpa ม.30/ม.33; RULING 4
    //
    // This is the happy-path integration chain that per-task TDD covered hop-by-hop
    // but never as a single end-to-end flow. The test proves:
    //  1. Both plan and log are applied (version:1) from a single push request
    //  2. GET /medication-plans returns the plan with the correct id
    //  3. GET /medication-logs returns the log with the correct id
    //  4. GET /account/export includes both in PDPA ม.30 portability output
    //  5. Tier-1 erasure deletes log first then plan (RULING 4 FK-safe order)
    //     — if the order were reversed, the JDBC DELETE on medication_plan would
    //       FK-violate the surviving medication_log row and throw an exception.
    // =========================================================================

    @Test
    void fullChain_pushPlanAndLog_getPlansAndLogs_export_fkSafeErasure() throws Exception {
        UUID planId = UUID.randomUUID();
        UUID logId  = UUID.randomUUID();

        // ── Step 1: Push plan + log in a single sync/push request ─────────────
        // medicationPlans is listed BEFORE medicationLogs in the LinkedHashMap so that
        // Jackson deserialises them in insertion order and the server processes the plan
        // first (guaranteeing the plan row exists when the log ownership check runs).
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanAndLogPushBody(planId, logId)))
                .andExpect(status().isOk())
                // Both applied with version:1
                .andExpect(jsonPath("$.applied[?(@.id=='" + planId + "')].version").value(1))
                .andExpect(jsonPath("$.applied[?(@.id=='" + logId  + "')].version").value(1))
                .andExpect(jsonPath("$.rejected").isEmpty());

        // Confirm both DB rows exist immediately after push
        assertThat(planRepo.findById(planId))
                .as("medication_plan row must be persisted by sync/push")
                .isPresent();
        assertThat(logRepo.findById(logId))
                .as("medication_log row must be persisted by sync/push")
                .isPresent();

        // ── Step 2: GET /medication-plans — plan appears in history view ───────
        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(planId.toString()))
                // INV-M3: name returned as opaque ciphertext (never decrypted by server)
                .andExpect(jsonPath("$.items[0].name").value(NAME_B64));

        // ── Step 3: GET /medication-logs — log appears in history view ─────────
        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(logId.toString()))
                .andExpect(jsonPath("$.items[0].status").value("taken"))
                .andExpect(jsonPath("$.items[0].medicationPlanId").value(planId.toString()));

        // ── Step 4: GET /account/export — both in PDPA ม.30 portability ───────
        mvc.perform(get("/account/export")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                // Plan in export
                .andExpect(jsonPath("$.medicationPlans").isArray())
                .andExpect(jsonPath("$.medicationPlans.length()").value(1))
                .andExpect(jsonPath("$.medicationPlans[0].id").value(planId.toString()))
                // Log in export
                .andExpect(jsonPath("$.medicationLogs").isArray())
                .andExpect(jsonPath("$.medicationLogs.length()").value(1))
                .andExpect(jsonPath("$.medicationLogs[0].id").value(logId.toString()));

        // ── Step 5: Tier-1 erasure — FK-safe order: log deleted BEFORE plan ───
        // Spec RULING 4: medication_log.medication_plan_id FK → medication_plan(id).
        // Deleting medication_plan first would violate this FK and crash erasure.
        // AccountErasureService.TIER1_CHILD_DELETE_ORDER lists medication_log before
        // medication_plan — this test confirms the production order is honoured.
        user.setDeletedAt(Instant.now().minus(200, ChronoUnit.DAYS));  // > 180d threshold
        user.setStatus("deleted");
        users.saveAndFlush(user);
        em.flush();   // write any remaining JPA changes to the DB within this transaction

        int purgedCount = erasureService.purgeExpiredAccountChildren(180);
        assertThat(purgedCount)
                .as("Exactly one eligible account should be purged")
                .isEqualTo(1);

        em.clear();   // evict JPA L1 cache so subsequent findById() re-queries the DB

        // Both rows must be gone — no FK exception means log was deleted first (RULING 4)
        assertThat(logRepo.findById(logId))
                .as("medication_log MUST be purged before medication_plan (FK constraint, RULING 4)")
                .isEmpty();
        assertThat(planRepo.findById(planId))
                .as("medication_plan must be purged by Tier-1 erasure (RULING 4 / ม.33)")
                .isEmpty();
    }

    // =========================================================================
    // GAP-MED-CONSENT-CHAIN — Consent denied:
    //   push(plan + log, general_health absent) → rejected → GET returns empty
    //
    // Spec: medication-behavior.md §A consent gate (first-fail-wins, per-collection);
    //       pdpa ม.1 (nothing egresses without consent)
    //
    // The per-task tests assert the rejected[] shape and DB absence, but do NOT then
    // call GET /medication-plans + GET /medication-logs to confirm the read path also
    // returns empty. This test closes that final link.
    // =========================================================================

    @Test
    void consentDenied_push_rejected_getPlansAndLogsEmpty() throws Exception {
        // Revoke general_health only; cloud_storage still granted
        when(consentChecker.isGranted(any(UUID.class), eq("general_health"))).thenReturn(false);
        when(consentChecker.isGranted(any(UUID.class), eq("cloud_storage"))).thenReturn(true);

        UUID planId = UUID.randomUUID();
        UUID logId  = UUID.randomUUID();

        // ── Step 1: Push with general_health absent → per-collection rejection ─
        // Both medicationPlans and medicationLogs share the same general_health gate
        // (per-collection consent, first-fail-wins within each collection).
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanAndLogPushBody(planId, logId)))
                .andExpect(status().isOk())
                // Per-collection rejection — id OMITTED (§A.3 consent shape)
                .andExpect(jsonPath("$.rejected[?(@.collection=='medicationPlans')].code")
                        .value("consent_required"))
                .andExpect(jsonPath("$.rejected[?(@.collection=='medicationPlans')].details")
                        .value("general_health"))
                .andExpect(jsonPath("$.rejected[?(@.collection=='medicationLogs')].code")
                        .value("consent_required"))
                .andExpect(jsonPath("$.applied").isEmpty());

        // DB must have NO rows (fail-closed: nothing persisted)
        assertThat(planRepo.findById(planId))
                .as("Consent-denied push must NOT persist the medication_plan row")
                .isEmpty();
        assertThat(logRepo.findById(logId))
                .as("Consent-denied push must NOT persist the medication_log row")
                .isEmpty();

        // ── Step 2: GET /medication-plans — must return empty (no leaked row) ──
        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());

        // ── Step 3: GET /medication-logs — must also return empty ─────────────
        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());

        // ── Step 4: GET /account/export — both arrays empty ───────────────────
        mvc.perform(get("/account/export")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medicationPlans").isArray())
                .andExpect(jsonPath("$.medicationPlans.length()").value(0))
                .andExpect(jsonPath("$.medicationLogs").isArray())
                .andExpect(jsonPath("$.medicationLogs.length()").value(0));
    }

    // =========================================================================
    // Builder helpers
    // =========================================================================

    /**
     * Builds a {@code POST /sync/push} body containing both a medicationPlan and a
     * medicationLog (the log references the plan via {@code medicationPlanId}).
     *
     * <p>medicationPlans appears BEFORE medicationLogs in the LinkedHashMap so that
     * Jackson preserves insertion order and the server processes the plan first.
     * This guarantees the plan row exists when the log ownership check (GUARD 4) runs.
     *
     * @param planId client-generated plan UUID
     * @param logId  client-generated log UUID
     */
    private String buildPlanAndLogPushBody(UUID planId, UUID logId) throws Exception {
        // Plan record (daily, one dose at 08:00)
        Map<String, Object> planRecord = new LinkedHashMap<>();
        planRecord.put("id", planId.toString());
        planRecord.put("version", 0);
        planRecord.put("active", true);
        planRecord.put("name", NAME_B64);

        Map<String, Object> planChanges = new LinkedHashMap<>();
        planChanges.put("created", List.of(planRecord));
        planChanges.put("updated", List.of());
        planChanges.put("deleted", List.of());

        // Log record — taken dose, references the plan above
        Map<String, Object> logRecord = new LinkedHashMap<>();
        logRecord.put("id", logId.toString());
        logRecord.put("version", 0);
        logRecord.put("status", "taken");
        logRecord.put("occurrenceTime", OCCURRENCE_TIME);
        logRecord.put("medicationPlanId", planId.toString());

        Map<String, Object> logChanges = new LinkedHashMap<>();
        logChanges.put("created", List.of(logRecord));
        logChanges.put("updated", List.of());
        logChanges.put("deleted", List.of());

        // medicationPlans MUST come before medicationLogs (insertion order = processing order)
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("medicationPlans", planChanges);
        changes.put("medicationLogs",  logChanges);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastPulledAt", Instant.now().toString());
        body.put("changes", changes);

        return objectMapper.writeValueAsString(body);
    }
}
