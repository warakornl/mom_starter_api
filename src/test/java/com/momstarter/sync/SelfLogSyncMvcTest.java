package com.momstarter.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.ConsentChecker;
import com.momstarter.selflog.SelfLog;
import com.momstarter.selflog.SelfLogRepository;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * MVC integration tests for the {@code selfLogs} sync collection.
 *
 * <p>Mirrors {@link KickCountSessionSyncMvcTest} for the immutable-create-only union pattern
 * (spec §A.1, ADR self-log-encryption-posture.md).
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Create weight self-log → applied[], version:1 (persists)</li>
 *   <li><strong>Idempotent union</strong>: re-push same id → applied[], version NOT bumped (D2)</li>
 *   <li><strong>empty_value</strong>: all four value fields null → rejected empty_value (ADR Decision 3 / G-2)</li>
 *   <li><strong>general_health consent gate</strong>: absent → per-collection reject, id omitted,
 *       rest of batch (supplyItems) still applies</li>
 *   <li><strong>Pull</strong>: {@code /sync/pull} returns {@code selfLogs} collection
 *       (confirms PULL_ORDER contains "selfLogs")</li>
 *   <li><strong>Tombstone</strong>: delete → applied[], all 4 value columns crypto-shredded to null (PDPA §4.4(A))</li>
 *   <li>metricType missing → metric_type_required</li>
 *   <li>metricType invalid → unknown_metric_type</li>
 *   <li>loggedAt missing → logged_at_required</li>
 *   <li>loggedAt malformed → logged_at_malformed</li>
 *   <li>note > 8 KB → note_too_large</li>
 * </ul>
 *
 * <p>Closed validation_error.details sub-code enum (ADR Decision 4 / G-3 ratified):
 * {@code unknown_metric_type} · {@code metric_type_required} · {@code logged_at_required} ·
 * {@code logged_at_malformed} · {@code note_too_large} · {@code empty_value}.
 * Backend emits + QA asserts these exact strings.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class SelfLogSyncMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private SelfLogRepository logs;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    private static final String LOGGED_AT = "2026-07-03T09:00";
    /** Minimal valid base64 bytes to populate valueNumeric (MVP plaintext bytes). */
    private static final String VALUE_NUMERIC_B64 =
            Base64.getEncoder().encodeToString(new byte[]{0x36, 0x34}); // "64" UTF-8 bytes

    @BeforeEach
    void setup() {
        logs.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("self-log-sync@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);
        // Default: all consents granted
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // Create — new weight log → applied[] version:1
    // -------------------------------------------------------------------------

    @Test
    void push_createWeightLog_applied_version1() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "weight", LOGGED_AT,
                VALUE_NUMERIC_B64, null, null, null, "kg", null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("selfLogs"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        SelfLog saved = logs.findById(id).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getMetricType()).isEqualTo("weight");
        assertThat(saved.getValueNumeric())
                .isEqualTo(Base64.getDecoder().decode(VALUE_NUMERIC_B64));
        assertThat(saved.getVersion()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // Idempotent union — re-push same id = no-op, version NOT bumped (D2)
    // -------------------------------------------------------------------------

    @Test
    void push_idempotentUnion_repushSameId_versionNotBumped() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "weight", LOGGED_AT,
                VALUE_NUMERIC_B64, null, null, null, "kg", null);
        String body = buildPushBody(List.of(record), List.of(), List.of());

        // First push — creates version:1
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        long versionAfterFirst = logs.findById(id).orElseThrow().getVersion();

        // Second push — same id, immutable event no-op
        MvcResult result = mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        // applied[] echoes current version (still 1), does NOT bump
        @SuppressWarnings("unchecked")
        Map<String, Object> responseMap = objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> appliedList = (List<Map<String, Object>>) responseMap.get("applied");
        assertThat(appliedList).hasSize(1);
        assertThat(((Number) appliedList.get(0).get("version")).longValue()).isEqualTo(1L);

        long versionAfterSecond = logs.findById(id).orElseThrow().getVersion();
        assertThat(versionAfterSecond).isEqualTo(versionAfterFirst); // NOT bumped

        // Only one row in DB (dedup by id — union-merge)
        assertThat(logs.findByUserIdAndIdIn(userId, List.of(id))).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // empty_value — all four value fields null → rejected (ADR Decision 3 / G-2)
    // No DB check; pure IS-NULL structural check in the apply path (reads no ciphertext)
    // -------------------------------------------------------------------------

    @Test
    void push_allNullValues_rejected_emptyValue() throws Exception {
        UUID id = UUID.randomUUID();
        // No valueNumeric, valueNumericSecondary, valueText, or note → all null
        Map<String, Object> record = buildRecord(id, "weight", LOGGED_AT,
                null, null, null, null, "kg", null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("selfLogs"))
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("empty_value"))
                .andExpect(jsonPath("$.applied").isEmpty());

        // No row persisted
        assertThat(logs.findById(id)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // general_health consent gate — per-collection reject; id omitted; rest of batch applies
    // -------------------------------------------------------------------------

    @Test
    void push_generalHealthConsentMissing_rejected_consentRequired_restOfBatchApplies()
            throws Exception {
        // Revoke general_health consent only; cloud_storage still granted
        when(consentChecker.isGranted(any(UUID.class), eq("general_health"))).thenReturn(false);
        when(consentChecker.isGranted(any(UUID.class), eq("cloud_storage"))).thenReturn(true);

        UUID selfLogId  = UUID.randomUUID();
        UUID supplyId   = UUID.randomUUID();

        Map<String, Object> selfLogRecord = buildRecord(selfLogId, "weight", LOGGED_AT,
                VALUE_NUMERIC_B64, null, null, null, "kg", null);

        // supplyItems is a non-health collection (no per-collection consent gate)
        Map<String, Object> supplyRecord = new LinkedHashMap<>();
        supplyRecord.put("id", supplyId.toString());
        supplyRecord.put("name", "Diapers S");
        supplyRecord.put("category", "diapers");
        supplyRecord.put("version", 0);

        // Build multi-collection push body: supplyItems first so it appears first in applied[]
        Map<String, Object> selfLogChanges = new LinkedHashMap<>();
        selfLogChanges.put("created", List.of(selfLogRecord));
        selfLogChanges.put("updated", List.of());
        selfLogChanges.put("deleted", List.of());

        Map<String, Object> supplyChanges = new LinkedHashMap<>();
        supplyChanges.put("created", List.of(supplyRecord));
        supplyChanges.put("updated", List.of());
        supplyChanges.put("deleted", List.of());

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("supplyItems", supplyChanges);   // processed first
        changes.put("selfLogs",    selfLogChanges);  // rejected due to consent

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastPulledAt", Instant.now().toString());
        body.put("changes", changes);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                // Per-collection consent reject — whole-collection entry, id OMITTED
                .andExpect(jsonPath("$.rejected[0].collection").value("selfLogs"))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("general_health"))
                .andExpect(jsonPath("$.rejected[0].id").doesNotExist())
                // Rest of batch (supplyItems) still applies
                .andExpect(jsonPath("$.applied[0].collection").value("supplyItems"));

        // selfLog row was NOT persisted (consent fail)
        assertThat(logs.findById(selfLogId)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Pull — selfLogs in change-set (confirms PULL_ORDER includes "selfLogs")
    // -------------------------------------------------------------------------

    @Test
    void pull_includesSelfLogs_inChanges() throws Exception {
        // Seed a live weight log directly (bypasses push consent gate)
        SelfLog s = new SelfLog();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setMetricType("weight");
        s.setLoggedAt(LocalDateTime.of(2026, 7, 3, 9, 0));
        s.setValueNumeric(new byte[]{0x36, 0x34});
        s.setUnit("kg");
        logs.save(s);

        mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.selfLogs").exists());
    }

    // -------------------------------------------------------------------------
    // Tombstone — delete → all 4 value columns crypto-shredded to null (PDPA §4.4(A))
    // -------------------------------------------------------------------------

    @Test
    void push_tombstone_allValueColumnsCryptoShredded() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] numericBytes     = new byte[]{0x01, 0x02};
        byte[] numeric2Bytes    = new byte[]{0x03, 0x04};
        byte[] textBytes        = new byte[]{0x05, 0x06};
        byte[] noteBytes        = new byte[]{0x07, 0x08};

        String numericB64  = Base64.getEncoder().encodeToString(numericBytes);
        String numeric2B64 = Base64.getEncoder().encodeToString(numeric2Bytes);
        String textB64     = Base64.getEncoder().encodeToString(textBytes);
        String noteB64     = Base64.getEncoder().encodeToString(noteBytes);

        // Push with all four value columns populated
        Map<String, Object> record = buildRecord(id, "blood_pressure", LOGGED_AT,
                numericB64, numeric2B64, textB64, noteB64, "mmHg", null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        SelfLog before = logs.findById(id).orElseThrow();
        assertThat(before.getValueNumeric()).isEqualTo(numericBytes);
        assertThat(before.getValueNumericSecondary()).isEqualTo(numeric2Bytes);
        assertThat(before.getValueText()).isEqualTo(textBytes);
        assertThat(before.getNoteCipher()).isEqualTo(noteBytes);

        // Tombstone — delete
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(), List.of(), List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()));

        SelfLog after = logs.findById(id).orElseThrow();
        assertThat(after.getDeletedAt()).isNotNull();
        // All four byte[] columns crypto-shredded (PDPA §4.4(A) / ruling 5a)
        assertThat(after.getValueNumeric()).isNull();
        assertThat(after.getValueNumericSecondary()).isNull();
        assertThat(after.getValueText()).isNull();
        assertThat(after.getNoteCipher()).isNull();
    }

    // -------------------------------------------------------------------------
    // Validation sub-code tests — frozen closed enum (ADR Decision 4 / G-3)
    // -------------------------------------------------------------------------

    @Test
    void push_missingMetricType_rejected_metricTypeRequired() throws Exception {
        UUID id = UUID.randomUUID();
        // omit metricType entirely
        Map<String, Object> record = buildRecord(id, null, LOGGED_AT,
                VALUE_NUMERIC_B64, null, null, null, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("metric_type_required"));
    }

    @Test
    void push_invalidMetricType_rejected_unknownMetricType() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "heartRate" /* invalid */, LOGGED_AT,
                VALUE_NUMERIC_B64, null, null, null, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("unknown_metric_type"));
    }

    @Test
    void push_missingLoggedAt_rejected_loggedAtRequired() throws Exception {
        UUID id = UUID.randomUUID();
        // omit loggedAt
        Map<String, Object> record = buildRecord(id, "weight", null,
                VALUE_NUMERIC_B64, null, null, null, "kg", null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("logged_at_required"));
    }

    @Test
    void push_malformedLoggedAt_rejected_loggedAtMalformed() throws Exception {
        UUID id = UUID.randomUUID();
        // loggedAt is not in "yyyy-MM-dd'T'HH:mm" format
        Map<String, Object> record = buildRecord(id, "weight", "not-a-date",
                VALUE_NUMERIC_B64, null, null, null, "kg", null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("logged_at_malformed"));
    }

    @Test
    void push_noteExceeds8KB_rejected_noteTooLarge() throws Exception {
        UUID id = UUID.randomUUID();
        // 8193 bytes > 8192 cap
        byte[] bigNote = new byte[8193];
        String bigNoteB64 = Base64.getEncoder().encodeToString(bigNote);

        Map<String, Object> record = buildRecord(id, "weight", LOGGED_AT,
                VALUE_NUMERIC_B64, null, null, bigNoteB64, "kg", null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("note_too_large"));
    }

    // -------------------------------------------------------------------------
    // Gate order G1: 413 batch_too_large BEFORE consent check
    // -------------------------------------------------------------------------

    @Test
    void gateOrder_G1_batchTooLarge_returns413_beforeConsentCheck() throws Exception {
        // Revoke cloud_storage so consent would return 403 — but 413 must win (G1)
        when(consentChecker.isGranted(any(UUID.class), eq("cloud_storage"))).thenReturn(false);

        List<Map<String, Object>> bigBatch = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            bigBatch.add(buildRecord(UUID.randomUUID(), "weight", LOGGED_AT,
                    VALUE_NUMERIC_B64, null, null, null, "kg", null));
        }

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(bigBatch, List.of(), List.of())))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("batch_too_large"));
    }

    // =========================================================================
    // Builder helpers
    // =========================================================================

    /**
     * Builds a push body with {@code selfLogs} in the changes map.
     */
    private String buildPushBody(List<Map<String, Object>> created,
                                  List<Map<String, Object>> updated,
                                  List<String> deleted) throws Exception {
        Map<String, Object> selfLogChanges = new LinkedHashMap<>();
        selfLogChanges.put("created", created);
        selfLogChanges.put("updated", updated);
        selfLogChanges.put("deleted", deleted);

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("selfLogs", selfLogChanges);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastPulledAt", Instant.now().toString());
        body.put("changes", changes);

        return objectMapper.writeValueAsString(body);
    }

    /**
     * Builds a self-log record for push.
     *
     * @param id                     client-generated UUID
     * @param metricType             "weight" | "blood_pressure" | "swelling" | "lochia" | "symptom" (or null to omit)
     * @param loggedAt               floating-civil "YYYY-MM-DDTHH:mm" (or null to omit)
     * @param valueNumericB64        base64-encoded bytes for valueNumeric (or null to omit)
     * @param valueNumericSecondaryB64 base64-encoded bytes for valueNumericSecondary (or null to omit)
     * @param valueTextB64           base64-encoded bytes for valueText (or null to omit)
     * @param noteB64                base64-encoded bytes for note_cipher (or null to omit)
     * @param unit                   display unit, e.g. "kg" or "mmHg" (or null to omit)
     * @param clientId               device UUID (or null to omit)
     */
    private Map<String, Object> buildRecord(UUID id, String metricType, String loggedAt,
                                             String valueNumericB64, String valueNumericSecondaryB64,
                                             String valueTextB64, String noteB64,
                                             String unit, UUID clientId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id.toString());
        r.put("version", 0);
        if (metricType != null)              r.put("metricType", metricType);
        if (loggedAt != null)                r.put("loggedAt", loggedAt);
        if (valueNumericB64 != null)         r.put("valueNumeric", valueNumericB64);
        if (valueNumericSecondaryB64 != null) r.put("valueNumericSecondary", valueNumericSecondaryB64);
        if (valueTextB64 != null)            r.put("valueText", valueTextB64);
        if (noteB64 != null)                 r.put("note", noteB64);
        if (unit != null)                    r.put("unit", unit);
        if (clientId != null)                r.put("clientId", clientId.toString());
        return r;
    }
}
