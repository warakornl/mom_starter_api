package com.momstarter.consumption;

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
 * MVC integration tests for the {@code consumptionMappings} sync collection.
 *
 * <p>PER-ROW consent: the collection has no collection-level health gate (perCollectionConsentType
 * returns null). Each row is individually gated inside applyUpsert based on its activityType.
 *
 * <p>Covers:
 * <ul>
 *   <li>Push feeding_formula mapping, dual consent → applied</li>
 *   <li>Push feeding_formula mapping, infant_feeding absent → per-row rejected consent_required</li>
 *   <li>Push diaper_change mapping, only general_health → applied</li>
 *   <li>Push bathing mapping, only general_health → applied</li>
 *   <li>Push diaper_change mapping, general_health absent → per-row rejected consent_required</li>
 *   <li>LWW mutable: version match → applied, version bumped</li>
 *   <li>LWW mutable: version mismatch → server_won conflict</li>
 *   <li>Tombstone: delete → applied (unconditional, tombstone-wins)</li>
 *   <li>Tombstone skeleton: delete never-seen id → applied (OQ-SYNC-10)</li>
 *   <li>Pull includes consumptionMappings</li>
 *   <li>GET /v1/consumption-mappings: general_health required; feeding_formula rows filtered
 *       when infant_feeding absent</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class ConsumptionMappingSyncMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private ConsumptionMappingRepository mappings;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper mapper;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String token;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setEmail("cm-sync-test@example.com");
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

    private Map<String, Object> mappingRecord(UUID id, String activityType, long baseVersion) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id.toString());
        r.put("activityType", activityType);
        r.put("supplyItemId", UUID.randomUUID().toString());
        r.put("defaultQty", 1);
        r.put("enabled", true);
        r.put("version", baseVersion);
        return r;
    }

    /**
     * Builds a push body for consumptionMappings.
     * {@code deleted} must be a list of UUID strings (not objects — the sync API contract
     * expects id strings in the deleted[] array, matching api-contract.md §8 / SyncService).
     */
    private String pushBody(List<Map<String, Object>> created,
                             List<Map<String, Object>> updated,
                             List<String> deleted) {
        try {
            Map<String, Object> cmChanges = new LinkedHashMap<>();
            cmChanges.put("created", created != null ? created : List.of());
            cmChanges.put("updated", updated != null ? updated : List.of());
            cmChanges.put("deleted", deleted != null ? deleted : List.of());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("lastPulledAt", Instant.now().minusSeconds(10).toString());
            body.put("changes", Map.of("consumptionMappings", cmChanges));
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Tests: per-row consent gating
    // -------------------------------------------------------------------------

    @Test
    void push_feedingFormula_dualConsentGranted_applied() throws Exception {
        grantDualConsent();
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(mappingRecord(id, "feeding_formula", 0)), null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.rejected").isEmpty());

        assertThat(mappings.findByUserIdAndIdIn(user.getId(), List.of(id))).hasSize(1);
    }

    @Test
    void push_feedingFormula_infantFeedingMissing_perRowRejected() throws Exception {
        grantOnlyGeneralHealth(); // infant_feeding NOT granted
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(mappingRecord(id, "feeding_formula", 0)), null, null)))
                .andExpect(status().isOk())
                // Per-row reject (not collection-level): consumptionMappings has no collection gate
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].collection").value("consumptionMappings"))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("infant_feeding"))
                .andExpect(jsonPath("$.applied").isEmpty());

        // Row must NOT be persisted
        assertThat(mappings.findByUserIdAndIdIn(user.getId(), List.of(id))).isEmpty();
    }

    @Test
    void push_diaperChange_generalHealthOnly_applied() throws Exception {
        grantOnlyGeneralHealth(); // general_health yes, infant_feeding no
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(mappingRecord(id, "diaper_change", 0)), null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected").isEmpty());
    }

    @Test
    void push_bathing_generalHealthOnly_applied() throws Exception {
        grantOnlyGeneralHealth();
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(mappingRecord(id, "bathing", 0)), null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected").isEmpty());
    }

    @Test
    void push_diaperChange_generalHealthMissing_perRowRejected() throws Exception {
        denyGeneralHealth(); // general_health NOT granted
        UUID id = UUID.randomUUID();

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(mappingRecord(id, "diaper_change", 0)), null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("general_health"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Tests: mutable LWW
    // -------------------------------------------------------------------------

    @Test
    void push_lww_versionMatch_applied_versionBumped() throws Exception {
        grantOnlyGeneralHealth();
        UUID id = UUID.randomUUID();

        // Create
        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(mappingRecord(id, "diaper_change", 0)), null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].version").value(1));

        // Update with base version = 1 (matches current)
        Map<String, Object> updateRecord = mappingRecord(id, "diaper_change", 1);
        updateRecord.put("defaultQty", 3);

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(null, List.of(updateRecord), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected").isEmpty());

        ConsumptionMapping updated = mappings.findByUserIdAndIdIn(user.getId(), List.of(id)).get(0);
        assertThat(updated.getDefaultQty()).isEqualTo(3);
        assertThat(updated.getVersion()).isGreaterThan(1L);
    }

    @Test
    void push_lww_versionMismatch_serverWonConflict() throws Exception {
        grantOnlyGeneralHealth();
        UUID id = UUID.randomUUID();

        // Create (version becomes 1)
        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(mappingRecord(id, "diaper_change", 0)), null, null)))
                .andExpect(status().isOk());

        // Update with stale base version = 0 (mismatch: server is at 1)
        Map<String, Object> staleRecord = mappingRecord(id, "diaper_change", 0);
        staleRecord.put("defaultQty", 99);

        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(null, List.of(staleRecord), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts[0].id").value(id.toString()))
                .andExpect(jsonPath("$.conflicts[0].resolution").value("server_won"))
                .andExpect(jsonPath("$.conflicts[0].serverRecord").exists())
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Tests: tombstone
    // -------------------------------------------------------------------------

    @Test
    void push_delete_appliesTombstone() throws Exception {
        grantOnlyGeneralHealth();
        UUID id = UUID.randomUUID();

        // Create
        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(List.of(mappingRecord(id, "diaper_change", 0)), null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()));

        // Delete (deleted[] is a list of UUID strings per contract)
        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(null, null, List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()));

        mappings.flush();
        ConsumptionMapping tombstoned = mappings.findById(id).orElseThrow();
        assertThat(tombstoned.getDeletedAt()).isNotNull();
    }

    @Test
    void push_deleteNeverSeenId_tombstoneSkeletonApplied() throws Exception {
        grantOnlyGeneralHealth();
        UUID id = UUID.randomUUID();

        // deleted[] is a list of UUID strings per contract
        mvc.perform(post("/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(null, null, List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1));

        assertThat(mappings.findById(id)).isPresent();
        assertThat(mappings.findById(id).get().getDeletedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Tests: pull
    // -------------------------------------------------------------------------

    @Test
    void pull_includesConsumptionMappings() throws Exception {
        grantOnlyGeneralHealth();

        ConsumptionMapping m = new ConsumptionMapping();
        m.setId(UUID.randomUUID());
        m.setUserId(user.getId());
        m.setActivityType("diaper_change");
        m.setDefaultQty(1);
        m.setEnabled(true);
        mappings.saveAndFlush(m);

        mvc.perform(get("/sync/pull")
                .header("Authorization", "Bearer " + token)
                .param("lastPulledAt", Instant.EPOCH.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.consumptionMappings.updated").isArray());
    }

    // -------------------------------------------------------------------------
    // Tests: GET /v1/consumption-mappings
    // -------------------------------------------------------------------------

    @Test
    void get_consumptionMappings_generalHealthGranted_returns200() throws Exception {
        grantOnlyGeneralHealth();

        mvc.perform(get("/consumption-mappings")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void get_consumptionMappings_generalHealthMissing_returns403() throws Exception {
        denyGeneralHealth();

        mvc.perform(get("/consumption-mappings")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_consumptionMappings_dualConsentAbsent_feedingFormulaRowsFiltered() throws Exception {
        // infant_feeding absent → feeding_formula rows must NOT appear in results
        grantOnlyGeneralHealth();

        // Persist directly (bypass consent check for setup)
        ConsumptionMapping formulaMapping = new ConsumptionMapping();
        formulaMapping.setId(UUID.randomUUID());
        formulaMapping.setUserId(user.getId());
        formulaMapping.setActivityType("feeding_formula");
        formulaMapping.setDefaultQty(4);
        formulaMapping.setEnabled(true);
        mappings.saveAndFlush(formulaMapping);

        ConsumptionMapping diaperMapping = new ConsumptionMapping();
        diaperMapping.setId(UUID.randomUUID());
        diaperMapping.setUserId(user.getId());
        diaperMapping.setActivityType("diaper_change");
        diaperMapping.setDefaultQty(1);
        diaperMapping.setEnabled(true);
        mappings.saveAndFlush(diaperMapping);

        var result = mvc.perform(get("/consumption-mappings")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        var json = mapper.readTree(body);
        var items = json.at("/items");

        // Only diaper_change should be returned; feeding_formula filtered out
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).at("/activityType").asText()).isEqualTo("diaper_change");
    }

    // -------------------------------------------------------------------------
    // Tests: activityType query param filter (contract §GET /consumption-mappings)
    // -------------------------------------------------------------------------

    @Test
    void get_activityTypeFilter_diaperChange_returnsOnlyDiaperChangeRows() throws Exception {
        // ?activityType=diaper_change must exclude bathing and feeding_formula rows
        grantDualConsent();

        ConsumptionMapping diaper = new ConsumptionMapping();
        diaper.setId(UUID.randomUUID());
        diaper.setUserId(user.getId());
        diaper.setActivityType("diaper_change");
        diaper.setDefaultQty(1);
        diaper.setEnabled(true);
        mappings.saveAndFlush(diaper);

        ConsumptionMapping bathing = new ConsumptionMapping();
        bathing.setId(UUID.randomUUID());
        bathing.setUserId(user.getId());
        bathing.setActivityType("bathing");
        bathing.setDefaultQty(1);
        bathing.setEnabled(true);
        mappings.saveAndFlush(bathing);

        ConsumptionMapping formula = new ConsumptionMapping();
        formula.setId(UUID.randomUUID());
        formula.setUserId(user.getId());
        formula.setActivityType("feeding_formula");
        formula.setDefaultQty(2);
        formula.setEnabled(true);
        mappings.saveAndFlush(formula);

        var result = mvc.perform(get("/consumption-mappings")
                .header("Authorization", "Bearer " + token)
                .param("activityType", "diaper_change"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        var json = mapper.readTree(body);
        var items = json.at("/items");

        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).at("/activityType").asText()).isEqualTo("diaper_change");
    }

    @Test
    void get_activityTypeFilter_invalidValue_returns400_unknownActivityType() throws Exception {
        // Any value outside feeding_formula|diaper_change|bathing must be rejected with 400
        grantOnlyGeneralHealth();

        mvc.perform(get("/consumption-mappings")
                .header("Authorization", "Bearer " + token)
                .param("activityType", "invalid_value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.details").value("unknown_activity_type"));
    }

    @Test
    void get_activityTypeFilter_feedingFormula_infantFeedingAbsent_returnsEmpty() throws Exception {
        // activityType=feeding_formula + no infant_feeding consent → consent filtering removes
        // feeding_formula rows first, then activityType filter is applied → empty result (not 403)
        grantOnlyGeneralHealth(); // infant_feeding NOT granted

        ConsumptionMapping formula = new ConsumptionMapping();
        formula.setId(UUID.randomUUID());
        formula.setUserId(user.getId());
        formula.setActivityType("feeding_formula");
        formula.setDefaultQty(2);
        formula.setEnabled(true);
        mappings.saveAndFlush(formula);

        var result = mvc.perform(get("/consumption-mappings")
                .header("Authorization", "Bearer " + token)
                .param("activityType", "feeding_formula"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        var json = mapper.readTree(body);
        var items = json.at("/items");

        // Consent filter strips feeding_formula rows; activityType filter then sees 0 remaining
        assertThat(items.size()).isEqualTo(0);
    }
}
