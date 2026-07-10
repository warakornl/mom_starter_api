package com.momstarter.feeding;

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
 * MVC integration tests for the {@code feedingSessions} sync collection.
 *
 * <p>Covers:
 * <ul>
 *   <li>Push formula session with dual consent (general_health + infant_feeding) → applied, version:1</li>
 *   <li>Push breastfeed session (no amountSubUnits) → applied</li>
 *   <li>Push formula session, {@code infant_feeding} consent missing → collection rejected
 *       {@code consent_required / infant_feeding}</li>
 *   <li>Push formula session, {@code general_health} consent missing → collection rejected
 *       {@code consent_required / general_health}</li>
 *   <li>Push formula session with amountSubUnits=0 → applied (zero is valid)</li>
 *   <li>Push breastfeed with amountSubUnits set → rejected {@code validation_error /
 *       amount_sub_units_formula_only}</li>
 *   <li>Push formula session without startedAt → rejected {@code validation_error /
 *       started_at_required}</li>
 *   <li>Push session with invalid kind → rejected {@code validation_error / kind_invalid}</li>
 *   <li>Re-push same id (idempotent no-op) → applied, version NOT bumped</li>
 *   <li>Tombstone: delete → applied, note_cipher null (crypto-shred)</li>
 *   <li>Tombstone skeleton: delete never-seen id → applied (OQ-SYNC-10)</li>
 *   <li>Pull includes {@code feedingSessions}</li>
 *   <li>GET /v1/feeding-sessions requires dual consent → 403 when either absent</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class FeedingSessionSyncMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private FeedingSessionRepository sessions;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper mapper;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String token;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setEmail("feeding-sync-test@example.com");
        user.setEmailVerified(true);
        users.saveAndFlush(user);
        token = jwtService.issueAccessToken(user.getId(), true);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void grantDualConsent() {
        when(consentChecker.isGranted(any(UUID.class), eq("cloud_storage"))).thenReturn(true);
        when(consentChecker.isGranted(any(UUID.class), eq("general_health"))).thenReturn(true);
        when(consentChecker.isGranted(any(UUID.class), eq("infant_feeding"))).thenReturn(true);
    }

    private void grantOnlyGeneralHealth() {
        when(consentChecker.isGranted(any(UUID.class), eq("cloud_storage"))).thenReturn(true);
        when(consentChecker.isGranted(any(UUID.class), eq("general_health"))).thenReturn(true);
        when(consentChecker.isGranted(any(UUID.class), eq("infant_feeding"))).thenReturn(false);
    }

    private void denyGeneralHealth() {
        when(consentChecker.isGranted(any(UUID.class), eq("cloud_storage"))).thenReturn(true);
        when(consentChecker.isGranted(any(UUID.class), eq("general_health"))).thenReturn(false);
        when(consentChecker.isGranted(any(UUID.class), eq("infant_feeding"))).thenReturn(false);
    }

    private Map<String, Object> formulaRecord(UUID id, String startedAt, Integer amountSubUnits) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id.toString());
        r.put("kind", "formula");
        r.put("startedAt", startedAt);
        if (amountSubUnits != null) r.put("amountSubUnits", amountSubUnits);
        r.put("version", 0);
        return r;
    }

    private Map<String, Object> breastfeedRecord(UUID id, String startedAt) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id.toString());
        r.put("kind", "breastfeed");
        r.put("side", "left");
        r.put("startedAt", startedAt);
        r.put("durationSeconds", 600);
        r.put("version", 0);
        return r;
    }

    /**
     * Builds a push body for feedingSessions.
     * {@code created} is a list of record maps; {@code deleted} is a list of UUID strings
     * (not objects — the sync API expects id strings in the deleted[] array, matching the
     * contract at api-contract.md §8 / SyncService.applyDeletes).
     */
    private String pushBody(List<Map<String, Object>> created, List<String> deleted) {
        try {
            Map<String, Object> feedingChanges = new LinkedHashMap<>();
            feedingChanges.put("created", created != null ? created : List.of());
            feedingChanges.put("updated", List.of());
            feedingChanges.put("deleted", deleted != null ? deleted : List.of());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("lastPulledAt", Instant.now().minusSeconds(10).toString());
            body.put("changes", Map.of("feedingSessions", feedingChanges));
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Tests: push — consent gating
    // -------------------------------------------------------------------------

    @Test
    void push_formulaSession_dualConsentGranted_applied() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(formulaRecord(id, "2026-07-10T08:00", 4)), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.rejected").isEmpty());

        // Verify persisted in DB
        assertThat(sessions.findByUserIdAndIdIn(user.getId(), List.of(id))).hasSize(1);
        assertThat(sessions.findByUserIdAndIdIn(user.getId(), List.of(id))
                .get(0).getAmountSubUnits()).isEqualTo(4);
    }

    @Test
    void push_breastfeedSession_dualConsentGranted_applied() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(breastfeedRecord(id, "2026-07-10T07:30")), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.rejected").isEmpty());
    }

    @Test
    void push_formulaSession_infantFeedingMissing_collectionRejected() throws Exception {
        grantOnlyGeneralHealth(); // general_health yes, infant_feeding NO
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(formulaRecord(id, "2026-07-10T08:00", 3)), null)))
                .andExpect(status().isOk())
                // Collection-level reject: both general_health (primary) already passes,
                // infant_feeding (additional) is missing → rejected at collection level
                .andExpect(jsonPath("$.rejected[0].collection").value("feedingSessions"))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("infant_feeding"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    @Test
    void push_formulaSession_generalHealthMissing_collectionRejected() throws Exception {
        denyGeneralHealth(); // general_health NO → primary consent fails first
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(formulaRecord(id, "2026-07-10T08:00", 3)), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("feedingSessions"))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("general_health"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Tests: push — validation guards
    // -------------------------------------------------------------------------

    @Test
    void push_formulaSession_amountSubUnitsZero_accepted() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(formulaRecord(id, "2026-07-10T08:00", 0)), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected").isEmpty());
    }

    @Test
    void push_breastfeedSession_amountSubUnitsSet_rejected() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        Map<String, Object> badRecord = new LinkedHashMap<>();
        badRecord.put("id", id.toString());
        badRecord.put("kind", "breastfeed");
        badRecord.put("startedAt", "2026-07-10T08:00");
        badRecord.put("amountSubUnits", 3); // invalid: breastfeed must not carry sub-units
        badRecord.put("version", 0);

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(badRecord), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("amount_sub_units_formula_only"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    @Test
    void push_formulaSession_startedAtMissing_rejected() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        Map<String, Object> badRecord = new LinkedHashMap<>();
        badRecord.put("id", id.toString());
        badRecord.put("kind", "formula");
        // no startedAt
        badRecord.put("version", 0);

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(badRecord), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("started_at_required"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    @Test
    void push_invalidKind_rejected() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        Map<String, Object> badRecord = new LinkedHashMap<>();
        badRecord.put("id", id.toString());
        badRecord.put("kind", "bottle"); // invalid
        badRecord.put("startedAt", "2026-07-10T08:00");
        badRecord.put("version", 0);

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(badRecord), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("kind_invalid"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    @Test
    void push_amountSubUnitsNegative_rejected() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        Map<String, Object> badRecord = new LinkedHashMap<>();
        badRecord.put("id", id.toString());
        badRecord.put("kind", "formula");
        badRecord.put("startedAt", "2026-07-10T08:00");
        badRecord.put("amountSubUnits", -1); // negative invalid
        badRecord.put("version", 0);

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(badRecord), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("amount_sub_units_range"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Tests: immutable-event union (idempotent re-push)
    // -------------------------------------------------------------------------

    @Test
    void push_rePushSameId_idempotentNoOp_versionUnchanged() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        // First push
        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(formulaRecord(id, "2026-07-10T08:00", 3)), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        // Second push of same id → idempotent no-op
        var result = mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(formulaRecord(id, "2026-07-10T08:00", 5 /*different amount — ignored*/)), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andReturn();

        // Version must still be 1 (immutable event — never overwritten)
        String body = result.getResponse().getContentAsString();
        var response = mapper.readTree(body);
        assertThat(response.at("/applied/0/version").asLong()).isEqualTo(1L);

        // DB still has only one row with the ORIGINAL amountSubUnits=3
        assertThat(sessions.findByUserIdAndIdIn(user.getId(), List.of(id))).hasSize(1);
        assertThat(sessions.findByUserIdAndIdIn(user.getId(), List.of(id))
                .get(0).getAmountSubUnits()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // Tests: tombstone (delete)
    // -------------------------------------------------------------------------

    @Test
    void push_delete_appliesTombstone_noteCipherCryptoShredded() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        // First push with a note
        byte[] noteBytes = new byte[]{1, 2, 3};
        Map<String, Object> record = formulaRecord(id, "2026-07-10T08:00", 2);
        record.put("note", Base64.getEncoder().encodeToString(noteBytes));

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(record), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()));

        // Verify note_cipher stored
        FeedingSession saved = sessions.findByUserIdAndIdIn(user.getId(), List.of(id)).get(0);
        assertThat(saved.getNoteCipher()).isNotNull();

        // Delete → tombstone with crypto-shred (deleted[] is a list of UUID strings per contract)
        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(null, List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()));

        sessions.flush();
        // Force reload from DB
        FeedingSession tombstoned = sessions.findById(id).orElseThrow();
        assertThat(tombstoned.getDeletedAt()).isNotNull();
        assertThat(tombstoned.getNoteCipher()).isNull(); // crypto-shredded
    }

    @Test
    void push_deleteNeverSeenId_tombstoneSkeletonApplied() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        // deleted[] is a list of UUID strings per contract
        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(null, List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1));

        // Tombstone skeleton exists in DB
        assertThat(sessions.findById(id)).isPresent();
        assertThat(sessions.findById(id).get().getDeletedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Tests: pull includes feedingSessions
    // -------------------------------------------------------------------------

    @Test
    void pull_includesFeedingSessions_withDualConsent() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        // Persist directly
        FeedingSession s = new FeedingSession();
        s.setId(id);
        s.setUserId(user.getId());
        s.setKind("formula");
        s.setStartedAt(LocalDateTime.of(2026, 7, 10, 8, 0));
        s.setAmountSubUnits(3);
        sessions.saveAndFlush(s);

        mvc.perform(get("/sync/pull")
                .header("Authorization", "Bearer " + token)
                .param("lastPulledAt", Instant.EPOCH.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.feedingSessions.updated").isArray());
    }

    // -------------------------------------------------------------------------
    // Tests: GET /v1/feeding-sessions consent gating
    // -------------------------------------------------------------------------

    @Test
    void get_feedingSessions_dualConsentGranted_returns200() throws Exception {
        grantDualConsent();

        mvc.perform(get("/v1/feeding-sessions")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void get_feedingSessions_infantFeedingMissing_returns403() throws Exception {
        grantOnlyGeneralHealth(); // infant_feeding not granted

        mvc.perform(get("/v1/feeding-sessions")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_feedingSessions_generalHealthMissing_returns403() throws Exception {
        denyGeneralHealth();

        mvc.perform(get("/v1/feeding-sessions")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
