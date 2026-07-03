package com.momstarter.selflog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.ConsentChecker;
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
 * Cross-layer integration tests for the Self-Log feature (Slice 1 Task 10 QA pass).
 *
 * <p>These tests are the <strong>cross-cutting gap-fillers</strong> identified in the
 * traceability matrix:
 * <ul>
 *   <li><strong>GAP-PDPA-3 (full chain)</strong>: no existing test exercised the complete
 *       pipeline {@code POST /sync/push} (selfLogs with general_health) → persisted in DB
 *       → {@code GET /self-logs} returns same record → {@code GET /account/export} includes
 *       it (PDPA ม.30/31 portability). This test closes that gap.</li>
 *   <li><strong>GAP-SD-5c (consent-denied variant)</strong>: the existing
 *       {@code SelfLogSyncMvcTest} asserts that a push is rejected when general_health is
 *       absent and that no DB row is persisted. But it does <em>not</em> then call
 *       {@code GET /self-logs} to confirm the list is empty (i.e., the rejected record
 *       genuinely did not leak through). This test closes that gap.</li>
 * </ul>
 *
 * <p>Both tests use real MVC / real H2 / real Flyway (same posture as
 * {@link SelfLogMvcTest} and {@link SelfLogSyncMvcTest}). ConsentChecker is mocked
 * to control the consent gate without a real consent_record row.
 *
 * <p>Spec refs: self-log-behavior.md §A.1/§A.2/§A.3; pdpa-assessment.md ruling 2.2/§1.4;
 * product-spec §3.3(b), US-10 AC, SD-5.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class SelfLogFullChainIntegrationTest {

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
    private static final String VALUE_NUMERIC_B64 =
            Base64.getEncoder().encodeToString(new byte[]{0x36, 0x34}); // "64" UTF-8

    @BeforeEach
    void setup() {
        logs.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("chain-test@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);
        // Default: all consents granted
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // =========================================================================
    // GAP-PDPA-3 — Full chain: push → persist → GET /self-logs → GET /account/export
    //
    // Spec: self-log-behavior.md §A.1 (push), §A.2 (GET), pdpa §1.4 (export ม.30/31)
    //
    // This is the happy-path integration path the per-task TDD covered hop-by-hop
    // but never as a single end-to-end flow. The test proves that a self-log pushed
    // via sync/push (with general_health granted):
    //  1. Returns applied[] with version:1
    //  2. Appears in GET /self-logs items[] (same id, same metricType)
    //  3. Appears in GET /account/export selfLogs[] (PDPA portability)
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void fullChain_push_getLog_export_includesSameRecord() throws Exception {
        UUID id = UUID.randomUUID();

        // ── Step 1: Push a weight self-log (general_health granted) ──────────
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(id, "weight", LOGGED_AT, VALUE_NUMERIC_B64, "kg")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("selfLogs"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.rejected").isEmpty());

        // Confirm DB row exists
        SelfLog saved = logs.findById(id).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getMetricType()).isEqualTo("weight");

        // ── Step 2: GET /self-logs — same record appears in the history view ──
        MvcResult getResult = mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].metricType").value("weight"))
                .andExpect(jsonPath("$.items[0].loggedAt").value(LOGGED_AT))
                // Server returns ciphertext as opaque Base64 — INV-S2: never decrypts
                .andExpect(jsonPath("$.items[0].valueNumeric").value(VALUE_NUMERIC_B64))
                .andReturn();

        // Confirm no interpretation fields in GET response (INV-S1/INV-S2)
        String getBody = getResult.getResponse().getContentAsString();
        assertThat(getBody)
                .doesNotContain("\"verdict\"")
                .doesNotContain("\"grade\"")
                .doesNotContain("\"colour\"")
                .doesNotContain("\"color\"");

        // ── Step 3: GET /account/export — record in PDPA ม.30 export ─────────
        mvc.perform(get("/account/export")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selfLogs").isArray())
                // The pushed self-log must appear in the export
                .andExpect(jsonPath("$.selfLogs.length()").value(1))
                .andExpect(jsonPath("$.selfLogs[0].metricType").value("weight"))
                .andExpect(jsonPath("$.selfLogs[0].valueNumeric").isNotEmpty());

        // Export must not leak the health data to another user (spot-check: account email matches)
        mvc.perform(get("/account/export")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(jsonPath("$.account.email").value("chain-test@example.com"));
    }

    // =========================================================================
    // GAP-SD-5c — Consent denied: push rejected → NOT persisted → GET /self-logs empty
    //
    // Spec: self-log-behavior.md §A.3 fail-closed; pdpa ruling 1 (nothing egresses
    // without consent). The prior SelfLogSyncMvcTest asserted the rejected[] shape
    // and absence of the DB row — but did NOT then call GET /self-logs to confirm the
    // read-path also returns empty. This test closes that final link.
    // =========================================================================

    @Test
    void consentDenied_push_rejected_getEmpty() throws Exception {
        // Revoke general_health only; cloud_storage still granted
        when(consentChecker.isGranted(any(UUID.class), eq("general_health"))).thenReturn(false);
        when(consentChecker.isGranted(any(UUID.class), eq("cloud_storage"))).thenReturn(true);

        UUID id = UUID.randomUUID();

        // ── Step 1: Push with general_health absent → per-collection rejection ──
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(id, "blood_pressure", LOGGED_AT, VALUE_NUMERIC_B64, "mmHg")))
                .andExpect(status().isOk())
                // Per-collection rejection; id OMITTED (§A.3 shape)
                .andExpect(jsonPath("$.rejected[0].collection").value("selfLogs"))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("general_health"))
                .andExpect(jsonPath("$.rejected[0].id").doesNotExist())
                .andExpect(jsonPath("$.applied").isEmpty());

        // DB must have NO row for this id (fail-closed: nothing persisted)
        assertThat(logs.findById(id))
                .as("Consent-denied push must NOT persist the self-log row")
                .isEmpty();

        // ── Step 2: GET /self-logs — must return empty (no leaked row) ──────
        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());

        // ── Step 3: GET /account/export — selfLogs must also be empty ────────
        mvc.perform(get("/account/export")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selfLogs").isArray())
                .andExpect(jsonPath("$.selfLogs.length()").value(0));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a minimal {@code POST /sync/push} body for a single selfLogs created record.
     *
     * <p>This helper is intentionally narrow (single record, single collection) — the
     * full-coverage multi-collection variant lives in {@link SelfLogSyncMvcTest}.
     */
    private String buildPushBody(UUID id, String metricType, String loggedAt,
                                  String valueNumericB64, String unit) throws Exception {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id.toString());
        record.put("version", 0);
        record.put("metricType", metricType);
        record.put("loggedAt", loggedAt);
        record.put("valueNumeric", valueNumericB64);
        record.put("unit", unit);

        Map<String, Object> selfLogChanges = new LinkedHashMap<>();
        selfLogChanges.put("created", List.of(record));
        selfLogChanges.put("updated", List.of());
        selfLogChanges.put("deleted", List.of());

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("selfLogs", selfLogChanges);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastPulledAt", Instant.now().toString());
        body.put("changes", changes);

        return objectMapper.writeValueAsString(body);
    }
}
