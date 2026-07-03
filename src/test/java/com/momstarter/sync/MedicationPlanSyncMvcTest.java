package com.momstarter.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.medication.MedicationLog;
import com.momstarter.medication.MedicationLogRepository;
import com.momstarter.medication.MedicationPlan;
import com.momstarter.medication.MedicationPlanRepository;
import com.momstarter.pregnancy.ConsentChecker;
import com.momstarter.supply.SupplyItem;
import com.momstarter.supply.SupplyItemRepository;
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
import java.time.LocalDateTime;
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
 * MVC integration tests for the {@code medicationPlans} sync collection.
 *
 * <p>Pattern: mutable-LWW (mirrors ExpenseSyncMvcTest). Per-collection consent gate
 * ({@code general_health}). Validation sub-codes from RULING 3 (ratified closed enum).
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Create plan → applied[], version:1</li>
 *   <li>Update plan (base version match) → applied, version bumped</li>
 *   <li>server_won conflict — stale base version</li>
 *   <li>tombstone_won conflict — update on tombstoned plan</li>
 *   <li>Tombstone (delete) → applied, name_cipher + dose_cipher crypto-shredded to null (§4.4(A))</li>
 *   <li>Tombstone of never-seen id → skeleton tombstone (OQ-SYNC-10)</li>
 *   <li>name_required — live plan with null name</li>
 *   <li>name_too_large — name_cipher exceeds 8 KB</li>
 *   <li>dose_too_large — dose_cipher exceeds 8 KB</li>
 *   <li>schedule_rule_invalid — missing freq</li>
 *   <li>schedule_rule_invalid — interval=1 on every_n_days (medication-specific rule)</li>
 *   <li>schedule_rule_null_valid — null scheduleRule is PRN, not an error</li>
 *   <li>general_health consent absent → per-collection reject, id omitted, rest of batch applies</li>
 *   <li>Pull confirms medicationPlans in PULL_ORDER</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class MedicationPlanSyncMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private MedicationPlanRepository planRepo;
    @Autowired private MedicationLogRepository logRepo;
    @Autowired private SupplyItemRepository supplyRepo;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    /** Valid base64-encoded name bytes — MVP plaintext bytes via no-op cipher. */
    private static final byte[] NAME_BYTES = "แอสไพริน".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String NAME_B64 = Base64.getEncoder().encodeToString(NAME_BYTES);

    /** Valid base64-encoded dose bytes. */
    private static final byte[] DOSE_BYTES = "100 mg".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String DOSE_B64 = Base64.getEncoder().encodeToString(DOSE_BYTES);

    /** Valid daily scheduleRule with startAt folded in (RULING 7.1). */
    private static Map<String, Object> validDailyRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("freq", "daily");
        rule.put("startAt", "2026-07-03T08:00");
        rule.put("timesOfDay", List.of("08:00"));
        return rule;
    }

    /** Valid every_n_days schedule rule with interval >= 2 (medication-specific). */
    private static Map<String, Object> validEveryNDaysRule(int interval) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("freq", "every_n_days");
        rule.put("startAt", "2026-07-03T08:00");
        rule.put("interval", interval);
        rule.put("timesOfDay", List.of("08:00"));
        return rule;
    }

    @BeforeEach
    void setup() {
        logRepo.deleteAll();
        planRepo.deleteAll();
        supplyRepo.deleteAll();
        users.deleteAll();

        user = new User();
        user.setEmail("med-plan-sync@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);

        // Default: all consents granted
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // CREATE — new plan → applied[], version:1
    // -------------------------------------------------------------------------

    @Test
    void push_createPlan_applied_version1() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(buildPlanRecord(id, 0L, NAME_B64, DOSE_B64, validDailyRule(), true)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("medicationPlans"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        MedicationPlan saved = planRepo.findById(id).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getNameCipher()).isEqualTo(NAME_BYTES);
        assertThat(saved.getDoseCipher()).isEqualTo(DOSE_BYTES);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getVersion()).isEqualTo(1L);
        assertThat(saved.getDeletedAt()).isNull();
    }

    // -------------------------------------------------------------------------
    // UPDATE — version match → applied, version bumped (LWW S-A)
    // -------------------------------------------------------------------------

    @Test
    void push_updatePlan_versionMatch_applied_versionBumped() throws Exception {
        UUID id = UUID.randomUUID();

        // First push — creates version:1
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(buildPlanRecord(id, 0L, NAME_B64, DOSE_B64, null, true)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        // Second push — update with base version=1 (matching current)
        byte[] updatedNameBytes = "ไอบูโพรเฟน".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String updatedNameB64 = Base64.getEncoder().encodeToString(updatedNameBytes);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(),
                                List.of(buildPlanRecord(id, 1L, updatedNameB64, null, validDailyRule(), true)),
                                List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("medicationPlans"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(2))
                .andExpect(jsonPath("$.conflicts").isEmpty());

        MedicationPlan updated = planRepo.findById(id).orElseThrow();
        assertThat(updated.getNameCipher()).isEqualTo(updatedNameBytes);
        assertThat(updated.getVersion()).isEqualTo(2L);
    }

    // -------------------------------------------------------------------------
    // SERVER_WON conflict — stale base version
    // -------------------------------------------------------------------------

    @Test
    void push_serverWonConflict_staleBaseVersion() throws Exception {
        UUID id = UUID.randomUUID();

        // Create version:1
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(buildPlanRecord(id, 0L, NAME_B64, null, null, true)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        // Push with base version=0 (stale — server is at 1)
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(),
                                List.of(buildPlanRecord(id, 0L, NAME_B64, null, null, true)),
                                List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts[0].collection").value("medicationPlans"))
                .andExpect(jsonPath("$.conflicts[0].id").value(id.toString()))
                .andExpect(jsonPath("$.conflicts[0].resolution").value("server_won"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // TOMBSTONE — delete plan → applied, name_cipher + dose_cipher crypto-shredded
    // -------------------------------------------------------------------------

    @Test
    void push_tombstone_plan_cryptoShredsNameAndDose() throws Exception {
        UUID id = UUID.randomUUID();

        // Create plan with both cipher fields populated
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(buildPlanRecord(id, 0L, NAME_B64, DOSE_B64, null, true)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        MedicationPlan before = planRepo.findById(id).orElseThrow();
        assertThat(before.getNameCipher()).isEqualTo(NAME_BYTES);
        assertThat(before.getDoseCipher()).isEqualTo(DOSE_BYTES);

        // Tombstone — delete
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(List.of(), List.of(), List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("medicationPlans"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()));

        MedicationPlan after = planRepo.findById(id).orElseThrow();
        assertThat(after.getDeletedAt()).isNotNull();
        // Crypto-shred: both cipher columns must be NULL (PDPA §4.4(A) / RULING 1)
        assertThat(after.getNameCipher())
                .as("name_cipher must be crypto-shredded on tombstone (§4.4(A))")
                .isNull();
        assertThat(after.getDoseCipher())
                .as("dose_cipher must be crypto-shredded on tombstone (§4.4(A))")
                .isNull();
    }

    // -------------------------------------------------------------------------
    // Skeleton tombstone — delete of never-seen id (OQ-SYNC-10)
    // -------------------------------------------------------------------------

    @Test
    void push_tombstone_neverSeenId_insertsSkeleton() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(List.of(), List.of(), List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("medicationPlans"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1));

        MedicationPlan skeleton = planRepo.findById(id).orElseThrow();
        assertThat(skeleton.getDeletedAt()).isNotNull();
        // Skeleton tombstone: name_cipher is null (tombstone allowed null name by CHECK)
        assertThat(skeleton.getNameCipher()).isNull();
    }

    // -------------------------------------------------------------------------
    // tombstone_won conflict — update attempt on tombstoned plan
    // -------------------------------------------------------------------------

    @Test
    void push_tombstoneWonConflict_updateOnTombstonedPlan() throws Exception {
        UUID id = UUID.randomUUID();

        // Create and then tombstone
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(buildPlanRecord(id, 0L, NAME_B64, null, null, true)),
                                List.of(), List.of())))
                .andExpect(status().isOk());

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(List.of(), List.of(), List.of(id.toString()))))
                .andExpect(status().isOk());

        // Try to update the tombstoned plan
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(),
                                List.of(buildPlanRecord(id, 1L, NAME_B64, null, null, true)),
                                List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts[0].resolution").value("tombstone_won"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // name_required — live plan with null name (RULING 3)
    // -------------------------------------------------------------------------

    @Test
    void push_liveNameNull_rejected_nameRequired() throws Exception {
        UUID id = UUID.randomUUID();
        // No name field in record → null name_cipher
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id.toString());
        record.put("version", 0);
        record.put("active", true);
        // name intentionally omitted

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("medicationPlans"))
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("name_required"))
                .andExpect(jsonPath("$.applied").isEmpty());

        assertThat(planRepo.findById(id)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // name_too_large — name_cipher > 8 KB (RULING 3)
    // -------------------------------------------------------------------------

    @Test
    void push_nameTooLarge_rejected_nameTooLarge() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] bigName = new byte[8193]; // 1 byte over the 8 KB cap
        String bigNameB64 = Base64.getEncoder().encodeToString(bigName);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(buildPlanRecord(id, 0L, bigNameB64, null, null, true)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("name_too_large"));

        assertThat(planRepo.findById(id)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // dose_too_large — dose_cipher > 8 KB (RULING 3)
    // -------------------------------------------------------------------------

    @Test
    void push_doseTooLarge_rejected_doseTooLarge() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] bigDose = new byte[8193];
        String bigDoseB64 = Base64.getEncoder().encodeToString(bigDose);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(buildPlanRecord(id, 0L, NAME_B64, bigDoseB64, null, true)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("dose_too_large"));
    }

    // -------------------------------------------------------------------------
    // schedule_rule_invalid — missing freq (RULING 3 / 7.1)
    // -------------------------------------------------------------------------

    @Test
    void push_scheduleRuleInvalid_missingFreq_rejected() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> badRule = new LinkedHashMap<>();
        badRule.put("startAt", "2026-07-03T08:00");
        badRule.put("timesOfDay", List.of("08:00"));
        // freq missing

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(buildPlanRecord(id, 0L, NAME_B64, null, badRule, true)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("schedule_rule_invalid"));

        assertThat(planRepo.findById(id)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // schedule_rule_invalid — interval=1 on every_n_days (medication-specific rule)
    // Server REJECTS interval=1 (canonicalise-to-daily is client concern, RULING 7.1)
    // -------------------------------------------------------------------------

    @Test
    void push_scheduleRuleInvalid_interval1OnEveryNDays_rejected() throws Exception {
        UUID id = UUID.randomUUID();
        // interval=1 with every_n_days is INVALID for medication (must be >= 2)
        Map<String, Object> badRule = validEveryNDaysRule(1); // interval=1 → rejected

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(buildPlanRecord(id, 0L, NAME_B64, null, badRule, true)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("schedule_rule_invalid"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // schedule_rule valid with interval >= 2 on every_n_days
    // -------------------------------------------------------------------------

    @Test
    void push_scheduleRuleValid_interval2OnEveryNDays_accepted() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> validRule = validEveryNDaysRule(2); // interval=2 → valid

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(
                                List.of(buildPlanRecord(id, 0L, NAME_B64, null, validRule, true)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.rejected").isEmpty());
    }

    // -------------------------------------------------------------------------
    // schedule_rule = null → PRN plan (valid, no error)
    // -------------------------------------------------------------------------

    @Test
    void push_scheduleRuleNull_isValidPrnPlan() throws Exception {
        UUID id = UUID.randomUUID();
        // scheduleRule not included in record = null/absent → PRN = valid
        Map<String, Object> record = buildPlanRecord(id, 0L, NAME_B64, null, null, true);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPlanPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.rejected").isEmpty());

        MedicationPlan saved = planRepo.findById(id).orElseThrow();
        assertThat(saved.getScheduleRule()).isNull();
    }

    // -------------------------------------------------------------------------
    // general_health consent absent → per-collection reject; id omitted; rest applies
    // -------------------------------------------------------------------------

    @Test
    void push_generalHealthConsentAbsent_perCollectionReject_restOfBatchApplies()
            throws Exception {
        // Revoke general_health only; cloud_storage still granted
        when(consentChecker.isGranted(any(UUID.class), eq("general_health"))).thenReturn(false);
        when(consentChecker.isGranted(any(UUID.class), eq("cloud_storage"))).thenReturn(true);

        UUID planId   = UUID.randomUUID();
        UUID supplyId = UUID.randomUUID();

        Map<String, Object> planRecord = buildPlanRecord(planId, 0L, NAME_B64, null, null, true);

        Map<String, Object> supplyRecord = new LinkedHashMap<>();
        supplyRecord.put("id", supplyId.toString());
        supplyRecord.put("name", "Diapers S");
        supplyRecord.put("category", "diapers");
        supplyRecord.put("version", 0);

        Map<String, Object> planChanges = new LinkedHashMap<>();
        planChanges.put("created", List.of(planRecord));
        planChanges.put("updated", List.of());
        planChanges.put("deleted", List.of());

        Map<String, Object> supplyChanges = new LinkedHashMap<>();
        supplyChanges.put("created", List.of(supplyRecord));
        supplyChanges.put("updated", List.of());
        supplyChanges.put("deleted", List.of());

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("supplyItems", supplyChanges);       // non-health, applies
        changes.put("medicationPlans", planChanges);     // health, consent rejected

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastPulledAt", Instant.now().toString());
        body.put("changes", changes);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                // Per-collection consent reject — whole-collection, id OMITTED
                .andExpect(jsonPath("$.rejected[0].collection").value("medicationPlans"))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("general_health"))
                .andExpect(jsonPath("$.rejected[0].id").doesNotExist())
                // Rest of batch (supplyItems) still applied
                .andExpect(jsonPath("$.applied[0].collection").value("supplyItems"));

        // Medication plan was NOT persisted
        assertThat(planRepo.findById(planId)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Pull — medicationPlans appears in PULL_ORDER (confirms SyncCollectionRegistry)
    // -------------------------------------------------------------------------

    @Test
    void pull_includesMedicationPlans_inChanges() throws Exception {
        // Seed a live plan directly (bypasses push consent gate)
        MedicationPlan plan = new MedicationPlan();
        plan.setId(UUID.randomUUID());
        plan.setUserId(userId);
        plan.setNameCipher(NAME_BYTES);
        plan.setActive(true);
        planRepo.save(plan);

        mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.medicationPlans").exists());
    }

    // =========================================================================
    // Builder helpers
    // =========================================================================

    private String buildPlanPushBody(List<Map<String, Object>> created,
                                     List<Map<String, Object>> updated,
                                     List<String> deleted) throws Exception {
        Map<String, Object> planChanges = new LinkedHashMap<>();
        planChanges.put("created", created);
        planChanges.put("updated", updated);
        planChanges.put("deleted", deleted);

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("medicationPlans", planChanges);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastPulledAt", Instant.now().toString());
        body.put("changes", changes);

        return objectMapper.writeValueAsString(body);
    }

    /**
     * Builds a medication plan record for push.
     *
     * @param id           client-generated UUID
     * @param version      base version for LWW arbitration (0 for new records)
     * @param nameB64      base64-encoded name_cipher (null to omit)
     * @param doseB64      base64-encoded dose_cipher (null to omit)
     * @param scheduleRule schedule_rule map (null to omit — PRN)
     * @param active       active boolean
     */
    private Map<String, Object> buildPlanRecord(UUID id, long version,
                                                 String nameB64, String doseB64,
                                                 Map<String, Object> scheduleRule,
                                                 boolean active) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id.toString());
        r.put("version", version);
        r.put("active", active);
        if (nameB64 != null)      r.put("name", nameB64);
        if (doseB64 != null)      r.put("dose", doseB64);
        if (scheduleRule != null) r.put("scheduleRule", scheduleRule);
        return r;
    }
}
