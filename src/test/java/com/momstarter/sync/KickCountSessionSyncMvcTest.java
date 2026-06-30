package com.momstarter.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.kickcount.KickCountSession;
import com.momstarter.kickcount.KickCountSessionRepository;
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
 * MVC integration tests for the {@code kickCountSessions} sync collection.
 *
 * <p>Covers (api-contract "Kick count — session lifecycle, sync & gating" + functional spec §A):
 * <ul>
 *   <li>create completed session → applied[] version:1</li>
 *   <li><strong>Terminal-status guard</strong>: pushed in_progress → rejected non_terminal_status</li>
 *   <li><strong>Terminal-status guard</strong>: pushed cancelled → rejected non_terminal_status</li>
 *   <li><strong>B1 endedAt required</strong>: endedAt missing on completed → rejected ended_before_started</li>
 *   <li>endedAt &lt; startedAt → rejected ended_before_started</li>
 *   <li><strong>G2</strong>: movementCount missing → rejected movement_count_required</li>
 *   <li>movementCount &lt; 0 → rejected movement_count_range</li>
 *   <li>movementCount = 0 → accepted (INV-K2 / count=0 completed is valid)</li>
 *   <li>targetCount ≠ 10 → rejected target_count_locked</li>
 *   <li>startedAt missing → rejected started_at_required</li>
 *   <li>durationSeconds negative → rejected duration_range</li>
 *   <li>note &gt; 8 KB → rejected note_too_large</li>
 *   <li><strong>Idempotent union</strong>: re-push same id → applied[], version NOT bumped</li>
 *   <li><strong>Tombstone</strong>: delete → applied[], note_cipher crypto-shredded (null)</li>
 *   <li><strong>Verbatim (DRIFT-1)</strong>: durationSeconds + gestationalWeekAtStart stored as-is</li>
 *   <li>{@code general_health} consent missing → rejected consent_required</li>
 *   <li>pull includes {@code kickCountSessions} in changes</li>
 *   <li><strong>Gate order G1</strong>: 413 batch_too_large returned before 403 consent check</li>
 * </ul>
 *
 * <p>The FROZEN 8 sub-code enum (contract B2): {@code non_terminal_status},
 * {@code movement_count_required}, {@code movement_count_range}, {@code target_count_locked},
 * {@code started_at_required}, {@code ended_before_started}, {@code duration_range},
 * {@code note_too_large} — QA asserts these exact strings.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class KickCountSessionSyncMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private KickCountSessionRepository sessions;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    private static final String STARTED_AT = "2026-07-01T09:00";
    private static final String ENDED_AT   = "2026-07-01T09:15";

    @BeforeEach
    void setup() {
        sessions.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("kick-sync@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);
        // Default: all consents granted
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // Create — new completed session → applied[] version:1
    // -------------------------------------------------------------------------

    @Test
    void push_createCompletedSession_applied_version1() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "completed", STARTED_AT, ENDED_AT,
                10, 10, null, 900, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("kickCountSessions"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        KickCountSession saved = sessions.findById(id).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getMovementCount()).isEqualTo(10);
        assertThat(saved.getVersion()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // Terminal-status guard — frozen sub-code: non_terminal_status
    // -------------------------------------------------------------------------

    @Test
    void push_inProgress_rejected_nonTerminalStatus() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "in_progress", STARTED_AT, ENDED_AT,
                10, 10, null, 900, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("kickCountSessions"))
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("non_terminal_status"))
                .andExpect(jsonPath("$.applied").isEmpty());

        assertThat(sessions.findById(id)).isEmpty();
    }

    @Test
    void push_cancelled_rejected_nonTerminalStatus() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "cancelled", STARTED_AT, ENDED_AT,
                10, 10, null, 900, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("non_terminal_status"));
    }

    // -------------------------------------------------------------------------
    // B1 endedAt required on completed — frozen sub-code: ended_before_started
    // -------------------------------------------------------------------------

    @Test
    void push_endedAtMissing_onCompleted_rejected_endedBeforeStarted() throws Exception {
        // B1: endedAt is REQUIRED when status=completed
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "completed", STARTED_AT,
                null /* no endedAt */, 10, 10, null, 900, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("ended_before_started"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    @Test
    void push_endedAtBeforeStartedAt_rejected_endedBeforeStarted() throws Exception {
        UUID id = UUID.randomUUID();
        // endedAt is 1 hour BEFORE startedAt
        Map<String, Object> record = buildRecord(id, "completed", "2026-07-01T09:00",
                "2026-07-01T08:00", 10, 10, null, 900, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("ended_before_started"));
    }

    // -------------------------------------------------------------------------
    // G2 movementCount guards — frozen sub-codes: movement_count_required, movement_count_range
    // -------------------------------------------------------------------------

    @Test
    void push_movementCountMissing_rejected_movementCountRequired() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "completed", STARTED_AT, ENDED_AT,
                null /* no movementCount */, 10, null, 900, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("movement_count_required"));
    }

    @Test
    void push_movementCountNegative_rejected_movementCountRange() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "completed", STARTED_AT, ENDED_AT,
                -1 /* negative */, 10, null, 900, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("movement_count_range"));
    }

    @Test
    void push_movementCountZero_accepted_INV_K2() throws Exception {
        // INV-K2: count=0 completed is valid (finishing-before-target identical)
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "completed", STARTED_AT, ENDED_AT,
                0 /* zero */, 10, null, 900, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected").isEmpty());
    }

    // -------------------------------------------------------------------------
    // targetCount guard — frozen sub-code: target_count_locked
    // -------------------------------------------------------------------------

    @Test
    void push_targetCountNot10_rejected_targetCountLocked() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "completed", STARTED_AT, ENDED_AT,
                5, 9 /* not 10 */, null, 900, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("target_count_locked"));
    }

    // -------------------------------------------------------------------------
    // startedAt guard — frozen sub-code: started_at_required
    // -------------------------------------------------------------------------

    @Test
    void push_startedAtMissing_rejected_startedAtRequired() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "completed",
                null /* no startedAt */, ENDED_AT, 10, 10, null, 900, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("started_at_required"));
    }

    // -------------------------------------------------------------------------
    // durationSeconds guard — frozen sub-code: duration_range
    // -------------------------------------------------------------------------

    @Test
    void push_durationSecondsNegative_rejected_durationRange() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "completed", STARTED_AT, ENDED_AT,
                10, 10, null, -1 /* negative */, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("duration_range"));
    }

    // -------------------------------------------------------------------------
    // note size guard — frozen sub-code: note_too_large
    // -------------------------------------------------------------------------

    @Test
    void push_noteExceeds8KB_rejected_noteTooLarge() throws Exception {
        UUID id = UUID.randomUUID();
        // 8193 bytes > 8192 cap
        byte[] bigNote = new byte[8193];
        String bigNoteBase64 = Base64.getEncoder().encodeToString(bigNote);

        Map<String, Object> record = buildRecord(id, "completed", STARTED_AT, ENDED_AT,
                10, 10, null, 900, bigNoteBase64, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].details").value("note_too_large"));
    }

    // -------------------------------------------------------------------------
    // Idempotent union — re-push same id = no-op, version NOT bumped (D3)
    // -------------------------------------------------------------------------

    @Test
    void push_idempotentUnion_repushSameId_versionNotBumped() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "completed", STARTED_AT, ENDED_AT,
                10, 10, null, 900, null, null);
        String body = buildPushBody(List.of(record), List.of(), List.of());

        // First push — creates version:1
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        long versionAfterFirst = sessions.findById(id).orElseThrow().getVersion();

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

        long versionAfterSecond = sessions.findById(id).orElseThrow().getVersion();
        assertThat(versionAfterSecond).isEqualTo(versionAfterFirst); // NOT bumped
    }

    // -------------------------------------------------------------------------
    // Tombstone — delete with note_cipher crypto-shred
    // -------------------------------------------------------------------------

    @Test
    void push_tombstone_deletesSession_noteCipherCryptoShredded() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] cipher = new byte[]{0x01, 0x02, 0x03};
        String noteBase64 = Base64.getEncoder().encodeToString(cipher);

        // First push — create with note
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(
                                List.of(buildRecord(id, "completed", STARTED_AT, ENDED_AT,
                                        10, 10, null, 900, noteBase64, null)),
                                List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        KickCountSession before = sessions.findById(id).orElseThrow();
        assertThat(before.getNoteCipher()).isEqualTo(cipher);

        // Tombstone — delete
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(), List.of(), List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()));

        KickCountSession after = sessions.findById(id).orElseThrow();
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(after.getNoteCipher()).isNull(); // crypto-shredded
    }

    // -------------------------------------------------------------------------
    // Verbatim storage (DRIFT-1): durationSeconds + gestationalWeekAtStart
    // -------------------------------------------------------------------------

    @Test
    void push_verbatimStorage_durationAndGestationalWeek_notRecomputed() throws Exception {
        UUID id = UUID.randomUUID();
        // Use distinct values; server must store exactly these (DRIFT-1)
        Map<String, Object> record = buildRecord(id, "completed", STARTED_AT, ENDED_AT,
                7, 10, null, 4242 /* durationSeconds */, null, 36 /* gestationalWeekAtStart */);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        KickCountSession saved = sessions.findById(id).orElseThrow();
        assertThat(saved.getDurationSeconds()).isEqualTo(4242);
        assertThat(saved.getGestationalWeekAtStart()).isEqualTo(36);
        // Server NEVER recomputes these from startedAt/endedAt (DRIFT-1)
    }

    // -------------------------------------------------------------------------
    // general_health consent gate → rejected[consent_required]
    // -------------------------------------------------------------------------

    @Test
    void push_generalHealthConsentMissing_rejected_consentRequired() throws Exception {
        // Revoke general_health consent only; cloud_storage still granted
        when(consentChecker.isGranted(any(UUID.class), eq("general_health"))).thenReturn(false);
        when(consentChecker.isGranted(any(UUID.class), eq("cloud_storage"))).thenReturn(true);

        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildRecord(id, "completed", STARTED_AT, ENDED_AT,
                10, 10, null, 900, null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("kickCountSessions"))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("general_health"))
                // No id in per-collection consent reject (spec: "id omit")
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Pull — kickCountSessions in change-set
    // -------------------------------------------------------------------------

    @Test
    void pull_includesKickCountSessions_inChanges() throws Exception {
        // Seed a live session
        KickCountSession s = new KickCountSession();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setStartedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        s.setEndedAt(LocalDateTime.of(2026, 7, 1, 9, 15));
        s.setDurationSeconds(900);
        s.setMovementCount(10);
        s.setTargetCount(10);
        s.setStatus("completed");
        sessions.save(s);

        mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.kickCountSessions").exists());
    }

    // -------------------------------------------------------------------------
    // Gate order G1: 413 batch_too_large BEFORE 403 consent check
    // -------------------------------------------------------------------------

    @Test
    void gateOrder_G1_batchTooLarge_returns413_beforeConsentCheck() throws Exception {
        // Revoke cloud_storage so that consent would return 403 — but 413 must win first (G1)
        when(consentChecker.isGranted(any(UUID.class), eq("cloud_storage"))).thenReturn(false);

        // Build a batch with >1000 records
        List<Map<String, Object>> bigBatch = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            bigBatch.add(buildRecord(UUID.randomUUID(), "completed", STARTED_AT, ENDED_AT,
                    10, 10, null, 900, null, null));
        }

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(bigBatch, List.of(), List.of())))
                .andExpect(status().isPayloadTooLarge()) // 413, not 403
                .andExpect(jsonPath("$.code").value("batch_too_large"));
    }

    // =========================================================================
    // Builder helpers
    // =========================================================================

    /**
     * Builds a push body with kickCountSessions in the changes map.
     */
    private String buildPushBody(List<Map<String, Object>> created,
                                  List<Map<String, Object>> updated,
                                  List<String> deleted) throws Exception {
        Map<String, Object> kickCountChanges = new LinkedHashMap<>();
        kickCountChanges.put("created", created);
        kickCountChanges.put("updated", updated);
        kickCountChanges.put("deleted", deleted);

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("kickCountSessions", kickCountChanges);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastPulledAt", Instant.now().toString());
        body.put("changes", changes);

        return objectMapper.writeValueAsString(body);
    }

    /**
     * Builds a kick-count session record for push.
     *
     * @param id                     client-generated UUID
     * @param status                 "completed" / "in_progress" / "cancelled"
     * @param startedAt              floating-civil "YYYY-MM-DDTHH:mm" (or null to omit)
     * @param endedAt                floating-civil "YYYY-MM-DDTHH:mm" (or null to omit)
     * @param movementCount          int or null to omit
     * @param targetCount            int (should be 10)
     * @param clientId               device UUID (or null)
     * @param durationSeconds        int or null to omit
     * @param noteBase64             Base64-encoded note_cipher bytes (or null to omit)
     * @param gestationalWeekAtStart int or null to omit
     */
    private Map<String, Object> buildRecord(UUID id, String status,
                                             String startedAt, String endedAt,
                                             Object movementCount, Object targetCount,
                                             UUID clientId, Object durationSeconds,
                                             String noteBase64, Integer gestationalWeekAtStart) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id.toString());
        r.put("status", status);
        r.put("version", 0);
        if (startedAt != null) r.put("startedAt", startedAt);
        if (endedAt != null) r.put("endedAt", endedAt);
        if (movementCount != null) r.put("movementCount", movementCount);
        r.put("targetCount", targetCount);
        if (durationSeconds != null) r.put("durationSeconds", durationSeconds);
        if (noteBase64 != null) r.put("note", noteBase64);
        if (gestationalWeekAtStart != null) r.put("gestationalWeekAtStart", gestationalWeekAtStart);
        if (clientId != null) r.put("clientId", clientId.toString());
        return r;
    }
}
