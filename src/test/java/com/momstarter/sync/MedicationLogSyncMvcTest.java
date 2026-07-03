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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
 * MVC integration tests for the {@code medicationLogs} sync collection.
 *
 * <p>Pattern: immutable-event union (mirrors SelfLogSyncMvcTest). Per-collection
 * consent gate ({@code general_health}). Validation sub-codes from RULING 3 (ratified
 * closed enum). Ownership check on {@code medicationPlanId} (G-4 / D7).
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Create log → applied[], version:1; loggedAt assigned SERVER-SIDE (D5)</li>
 *   <li>Idempotent union: re-push same id → applied, version NOT bumped (D3)</li>
 *   <li>Tombstone → applied, note_cipher crypto-shredded (§4.4(A))</li>
 *   <li>Tombstone of never-seen id → skeleton tombstone (OQ-SYNC-10)</li>
 *   <li>status_invalid — missing or unknown status</li>
 *   <li>occurrence_time_required — missing occurrenceTime</li>
 *   <li>occurrence_time_malformed — not YYYY-MM-DDTHH:mm format</li>
 *   <li>note_too_large — note_cipher decoded bytes > 8 KB</li>
 *   <li>medication_plan_not_found — medicationPlanId from another user (IDOR / G-4)</li>
 *   <li>medication_plan_not_found — non-existent medicationPlanId</li>
 *   <li>ad-hoc log — null medicationPlanId is legal</li>
 *   <li>general_health consent absent → per-collection reject, id omitted, rest applies</li>
 *   <li>Pull confirms medicationLogs in PULL_ORDER</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class MedicationLogSyncMvcTest {

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

    private static final String OCCURRENCE_TIME = "2026-07-03T09:00";

    private static final byte[] NOTE_BYTES = "ตรวจสอบหมอ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String NOTE_B64 = Base64.getEncoder().encodeToString(NOTE_BYTES);

    @BeforeEach
    void setup() {
        logRepo.deleteAll();
        planRepo.deleteAll();
        supplyRepo.deleteAll();
        users.deleteAll();

        user = new User();
        user.setEmail("med-log-sync@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);

        // Default: all consents granted
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // Helper — create a live plan for ownership tests
    // -------------------------------------------------------------------------

    private MedicationPlan seedPlan(UUID userId) {
        MedicationPlan plan = new MedicationPlan();
        plan.setId(UUID.randomUUID());
        plan.setUserId(userId);
        plan.setNameCipher("test".getBytes());
        plan.setActive(true);
        return planRepo.saveAndFlush(plan);
    }

    // -------------------------------------------------------------------------
    // CREATE — new log → applied[], version:1; loggedAt is server-assigned
    // -------------------------------------------------------------------------

    @Test
    void push_createLog_applied_version1_loggedAtServerAssigned() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(
                                List.of(buildLogRecord(id, "taken", OCCURRENCE_TIME, null, null)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("medicationLogs"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        MedicationLog saved = logRepo.findById(id).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getStatus()).isEqualTo("taken");
        assertThat(saved.getVersion()).isEqualTo(1L);
        assertThat(saved.getDeletedAt()).isNull();
        // loggedAt must be server-assigned (D5) — non-null and close to now
        assertThat(saved.getLoggedAt()).isNotNull();
        assertThat(saved.getLoggedAt()).isAfter(Instant.now().minusSeconds(5));
    }

    // -------------------------------------------------------------------------
    // CREATE with note — note bytes stored correctly
    // -------------------------------------------------------------------------

    @Test
    void push_createLogWithNote_noteStored() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(
                                List.of(buildLogRecord(id, "missed", OCCURRENCE_TIME, NOTE_B64, null)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        MedicationLog saved = logRepo.findById(id).orElseThrow();
        assertThat(saved.getNoteCipher()).isEqualTo(NOTE_BYTES);
    }

    // -------------------------------------------------------------------------
    // Idempotent union — re-push same id = no-op, version NOT bumped (D3)
    // -------------------------------------------------------------------------

    @Test
    void push_idempotentUnion_repushSameId_versionNotBumped() throws Exception {
        UUID id = UUID.randomUUID();
        String body = buildLogPushBody(
                List.of(buildLogRecord(id, "taken", OCCURRENCE_TIME, null, null)),
                List.of(), List.of());

        // First push — creates version:1
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        long versionAfterFirst = logRepo.findById(id).orElseThrow().getVersion();

        // Second push — same id, immutable event no-op
        MvcResult result = mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        // applied[] echoes current version (still 1), version NOT bumped
        @SuppressWarnings("unchecked")
        Map<String, Object> responseMap = objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> appliedList = (List<Map<String, Object>>) responseMap.get("applied");
        assertThat(appliedList).hasSize(1);
        assertThat(((Number) appliedList.get(0).get("version")).longValue()).isEqualTo(1L);

        long versionAfterSecond = logRepo.findById(id).orElseThrow().getVersion();
        assertThat(versionAfterSecond).isEqualTo(versionAfterFirst); // NOT bumped

        // Only one row in DB
        assertThat(logRepo.findByUserIdAndIdIn(userId, List.of(id))).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // TOMBSTONE — delete log → applied, note_cipher crypto-shredded (§4.4(A))
    // -------------------------------------------------------------------------

    @Test
    void push_tombstone_log_noteCipherCryptoShredded() throws Exception {
        UUID id = UUID.randomUUID();

        // Create log with note populated
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(
                                List.of(buildLogRecord(id, "taken", OCCURRENCE_TIME, NOTE_B64, null)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        MedicationLog before = logRepo.findById(id).orElseThrow();
        assertThat(before.getNoteCipher()).isEqualTo(NOTE_BYTES);

        // Tombstone — delete
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(List.of(), List.of(), List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("medicationLogs"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()));

        MedicationLog after = logRepo.findById(id).orElseThrow();
        assertThat(after.getDeletedAt()).isNotNull();
        // note_cipher must be crypto-shredded (PDPA §4.4(A) / RULING 1)
        assertThat(after.getNoteCipher())
                .as("note_cipher must be null after tombstone (crypto-shred)")
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
                        .content(buildLogPushBody(List.of(), List.of(), List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("medicationLogs"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1));

        MedicationLog skeleton = logRepo.findById(id).orElseThrow();
        assertThat(skeleton.getDeletedAt()).isNotNull();
        assertThat(skeleton.getNoteCipher()).isNull();
    }

    // -------------------------------------------------------------------------
    // status_invalid — missing or invalid status (RULING 3)
    // -------------------------------------------------------------------------

    @Test
    void push_statusMissing_rejected_statusInvalid() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildLogRecord(id, null, OCCURRENCE_TIME, null, null);
        record.remove("status"); // explicitly omit

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("medicationLogs"))
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("status_invalid"));

        assertThat(logRepo.findById(id)).isEmpty();
    }

    @Test
    void push_statusInvalid_unknownValue_rejected_statusInvalid() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(
                                List.of(buildLogRecord(id, "skipped" /* invalid */, OCCURRENCE_TIME, null, null)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("status_invalid"));
    }

    // -------------------------------------------------------------------------
    // occurrence_time_required — missing occurrenceTime (RULING 3)
    // -------------------------------------------------------------------------

    @Test
    void push_occurrenceTimeMissing_rejected_occurrenceTimeRequired() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildLogRecord(id, "taken", null, null, null);
        record.remove("occurrenceTime"); // explicitly omit

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("occurrence_time_required"));
    }

    // -------------------------------------------------------------------------
    // occurrence_time_malformed — not YYYY-MM-DDTHH:mm (RULING 3)
    // -------------------------------------------------------------------------

    @Test
    void push_occurrenceTimeMalformed_rejected_occurrenceTimeMalformed() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(
                                List.of(buildLogRecord(id, "taken", "not-a-datetime", null, null)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("occurrence_time_malformed"));
    }

    // -------------------------------------------------------------------------
    // note_too_large — decoded note_cipher > 8 KB (RULING 3)
    // -------------------------------------------------------------------------

    @Test
    void push_noteTooLarge_rejected_noteTooLarge() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] bigNote = new byte[8193];
        String bigNoteB64 = Base64.getEncoder().encodeToString(bigNote);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(
                                List.of(buildLogRecord(id, "taken", OCCURRENCE_TIME, bigNoteB64, null)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("note_too_large"));
    }

    // -------------------------------------------------------------------------
    // medication_plan_not_found — medicationPlanId from ANOTHER user (IDOR / G-4)
    // -------------------------------------------------------------------------

    @Test
    void push_medicationPlanNotFound_anotherUsersPlanId_rejected() throws Exception {
        // Create a plan belonging to a DIFFERENT user
        User otherUser = new User();
        otherUser.setEmail("other-user@example.com");
        otherUser.setEmailVerified(true);
        otherUser = users.save(otherUser);
        UUID otherUserId = otherUser.getId();

        MedicationPlan otherPlan = seedPlan(otherUserId);

        // Push log for OUR user but referencing the OTHER user's plan
        UUID logId = UUID.randomUUID();
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(
                                List.of(buildLogRecord(logId, "taken", OCCURRENCE_TIME, null,
                                        otherPlan.getId())),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("medicationLogs"))
                .andExpect(jsonPath("$.rejected[0].id").value(logId.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("medication_plan_not_found"))
                .andExpect(jsonPath("$.applied").isEmpty());

        // No log row persisted
        assertThat(logRepo.findById(logId)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // medication_plan_not_found — non-existent medicationPlanId
    // -------------------------------------------------------------------------

    @Test
    void push_medicationPlanNotFound_nonExistentPlanId_rejected() throws Exception {
        UUID nonExistentPlanId = UUID.randomUUID();
        UUID logId = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(
                                List.of(buildLogRecord(logId, "taken", OCCURRENCE_TIME, null,
                                        nonExistentPlanId)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("medication_plan_not_found"));
    }

    // -------------------------------------------------------------------------
    // Ad-hoc log — null medicationPlanId is legal (E6)
    // -------------------------------------------------------------------------

    @Test
    void push_adHocLog_nullMedicationPlanId_isLegal() throws Exception {
        UUID id = UUID.randomUUID();
        // buildLogRecord with planId=null → ad-hoc log
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(
                                List.of(buildLogRecord(id, "taken", OCCURRENCE_TIME, null, null)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.rejected").isEmpty());

        MedicationLog saved = logRepo.findById(id).orElseThrow();
        assertThat(saved.getMedicationPlanId()).isNull();
    }

    // -------------------------------------------------------------------------
    // Log with valid medicationPlanId owned by this user — should apply
    // -------------------------------------------------------------------------

    @Test
    void push_validMedicationPlanId_ownedByUser_applied() throws Exception {
        MedicationPlan ownPlan = seedPlan(userId);
        UUID logId = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildLogPushBody(
                                List.of(buildLogRecord(logId, "taken", OCCURRENCE_TIME, null,
                                        ownPlan.getId())),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.rejected").isEmpty());

        MedicationLog saved = logRepo.findById(logId).orElseThrow();
        assertThat(saved.getMedicationPlanId()).isEqualTo(ownPlan.getId());
    }

    // -------------------------------------------------------------------------
    // general_health consent absent → per-collection reject; id omitted; rest applies
    // -------------------------------------------------------------------------

    @Test
    void push_generalHealthConsentAbsent_perCollectionReject_restOfBatchApplies()
            throws Exception {
        when(consentChecker.isGranted(any(UUID.class), eq("general_health"))).thenReturn(false);
        when(consentChecker.isGranted(any(UUID.class), eq("cloud_storage"))).thenReturn(true);

        UUID logId    = UUID.randomUUID();
        UUID supplyId = UUID.randomUUID();

        Map<String, Object> logRecord = buildLogRecord(logId, "taken", OCCURRENCE_TIME, null, null);

        Map<String, Object> supplyRecord = new LinkedHashMap<>();
        supplyRecord.put("id", supplyId.toString());
        supplyRecord.put("name", "Baby Wipes");
        supplyRecord.put("category", "hygiene");
        supplyRecord.put("version", 0);

        Map<String, Object> logChanges = new LinkedHashMap<>();
        logChanges.put("created", List.of(logRecord));
        logChanges.put("updated", List.of());
        logChanges.put("deleted", List.of());

        Map<String, Object> supplyChanges = new LinkedHashMap<>();
        supplyChanges.put("created", List.of(supplyRecord));
        supplyChanges.put("updated", List.of());
        supplyChanges.put("deleted", List.of());

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("supplyItems", supplyChanges);      // non-health, applies
        changes.put("medicationLogs", logChanges);      // health, consent rejected

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastPulledAt", Instant.now().toString());
        body.put("changes", changes);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("medicationLogs"))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("general_health"))
                .andExpect(jsonPath("$.rejected[0].id").doesNotExist())
                .andExpect(jsonPath("$.applied[0].collection").value("supplyItems"));

        assertThat(logRepo.findById(logId)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Pull — medicationLogs appears in PULL_ORDER
    // -------------------------------------------------------------------------

    @Test
    void pull_includesMedicationLogs_inChanges() throws Exception {
        // Seed a live log directly (bypasses push consent gate)
        MedicationLog log = new MedicationLog();
        log.setId(UUID.randomUUID());
        log.setUserId(userId);
        log.setStatus("taken");
        log.setOccurrenceTime(java.time.LocalDateTime.of(2026, 7, 3, 9, 0));
        logRepo.save(log);

        mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.medicationLogs").exists());
    }

    // =========================================================================
    // Builder helpers
    // =========================================================================

    private String buildLogPushBody(List<Map<String, Object>> created,
                                    List<Map<String, Object>> updated,
                                    List<String> deleted) throws Exception {
        Map<String, Object> logChanges = new LinkedHashMap<>();
        logChanges.put("created", created);
        logChanges.put("updated", updated);
        logChanges.put("deleted", deleted);

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("medicationLogs", logChanges);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastPulledAt", Instant.now().toString());
        body.put("changes", changes);

        return objectMapper.writeValueAsString(body);
    }

    /**
     * Builds a medication log record for push.
     *
     * @param id             client-generated UUID
     * @param status         "taken" | "missed" (or null to omit for negative tests)
     * @param occurrenceTime floating-civil "YYYY-MM-DDTHH:mm" (or null to omit)
     * @param noteB64        base64-encoded note_cipher (or null to omit)
     * @param medicationPlanId plan UUID (or null for ad-hoc log)
     */
    private Map<String, Object> buildLogRecord(UUID id, String status,
                                                String occurrenceTime,
                                                String noteB64,
                                                UUID medicationPlanId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id.toString());
        r.put("version", 0);
        if (status != null)          r.put("status", status);
        if (occurrenceTime != null)  r.put("occurrenceTime", occurrenceTime);
        if (noteB64 != null)         r.put("note", noteB64);
        if (medicationPlanId != null) r.put("medicationPlanId", medicationPlanId.toString());
        return r;
    }
}
