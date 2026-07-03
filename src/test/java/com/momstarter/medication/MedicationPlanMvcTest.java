package com.momstarter.medication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.ConsentChecker;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for {@code GET /v1/medication-plans} (read-only view, Slice 2 Task 4).
 *
 * <p>Covers (spec §A.2 / ADR G-4 RULING 7.4):
 * <ul>
 *   <li><strong>Empty = 200</strong> (not 404) with {@code items:[], nextCursor absent}</li>
 *   <li>Single plan with all fields: name/dose as Base64, scheduleRule as JSON object, active</li>
 *   <li>Tombstoned plans excluded ({@code deleted_at IS NULL})</li>
 *   <li><strong>IDOR</strong>: JWT subject is the only ownership scope (D7)</li>
 *   <li>Unverified email → 403 {@code email_unverified} (ADR G-4 RULING 7.4)</li>
 *   <li>Unauthenticated → 401</li>
 *   <li>Invalid cursor → 400 {@code invalid_cursor}</li>
 *   <li>Cursor pagination: {@code nextCursor} issued; continuation returns next page; all unique,
 *       no overlap, no missing rows across pages</li>
 *   <li>limit &gt; 500 silently clamped to 500</li>
 *   <li><strong>Identical updatedAt tie-break</strong>: {@code id DESC} tie-break through cursor
 *       returns all rows with the same updatedAt, no skip, no duplicate</li>
 * </ul>
 *
 * <p>Security: auth + {@code email_verified} only — NO {@code cloud_storage} gate on read
 * (ADR G-4 RULING 7.4). IDOR enforced structurally via JWT subject scope.
 * Ciphertext fields (name/dose) are opaque Base64 — server never decrypts.
 * NO from/to filter — plans have no event bucket key (spec §A.2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class MedicationPlanMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private MedicationPlanRepository planRepo;
    @Autowired private MedicationLogRepository logRepo;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    /** Mocked so sync push path does not NPE in the shared Spring context. */
    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    @BeforeEach
    void setup() {
        // Delete in FK order: medication_log FK → medication_plan FK → users
        logRepo.deleteAll();
        planRepo.deleteAll();
        users.deleteAll();

        user = new User();
        user.setEmail("medplan-read@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // 1. Empty collection = 200 with items:[] (NOT 404)
    // -------------------------------------------------------------------------

    @Test
    void get_noPlans_returns200_emptyItems() throws Exception {
        // Contract spec §A.2: empty = 200 {items:[], nextCursor absent} — NOT 404
        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 2. Single plan returned with all fields
    // -------------------------------------------------------------------------

    @Test
    void get_singlePlan_returned_withAllFields() throws Exception {
        byte[] nameBytes = "Folic Acid".getBytes();
        String expectedNameBase64 = Base64.getEncoder().encodeToString(nameBytes);
        byte[] doseBytes = "400mcg".getBytes();
        String expectedDoseBase64 = Base64.getEncoder().encodeToString(doseBytes);
        // scheduleRule stored as JSON string; returned as JSON object (JsonNode, not string literal)
        String scheduleJson = "{\"freq\":\"daily\",\"startAt\":\"2026-07-01T08:00\","
                + "\"timesOfDay\":[\"08:00\"]}";
        // sourceSuggestionStateId: non-null opaque soft-ref (RULING 2) — must round-trip in response
        UUID srcSuggId = UUID.randomUUID();

        MedicationPlan p = new MedicationPlan();
        p.setId(UUID.randomUUID());
        p.setUserId(userId);
        p.setNameCipher(nameBytes);
        p.setDoseCipher(doseBytes);
        p.setScheduleRule(scheduleJson);
        p.setActive(true);
        p.setSourceSuggestionStateId(srcSuggId);
        planRepo.save(p);

        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(p.getId().toString()))
                .andExpect(jsonPath("$.items[0].name").value(expectedNameBase64))
                .andExpect(jsonPath("$.items[0].dose").value(expectedDoseBase64))
                // scheduleRule returned as embedded JSON object (not string-encoded)
                .andExpect(jsonPath("$.items[0].scheduleRule.freq").value("daily"))
                .andExpect(jsonPath("$.items[0].active").value(true))
                .andExpect(jsonPath("$.items[0].version").isNumber())
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].updatedAt").isNotEmpty())
                // sourceSuggestionStateId: non-null → must appear in response (spec §A.2 / RULING 2)
                .andExpect(jsonPath("$.items[0].sourceSuggestionStateId")
                        .value(srcSuggId.toString()))
                // userId and clientId are internal-only — must NOT be present
                .andExpect(jsonPath("$.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.items[0].clientId").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 2b. PRN plan (null scheduleRule + null sourceSuggestionStateId) — both omitted
    //     (@JsonInclude NON_NULL path — spec §A.2 / RULING 2)
    // -------------------------------------------------------------------------

    @Test
    void get_prn_plan_nullScheduleRule_and_nullSourceSuggestionStateId_omitted() throws Exception {
        // buildAndSave leaves both scheduleRule and sourceSuggestionStateId null
        buildAndSave(userId);

        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                // PRN/ad-hoc plan: scheduleRule null → omitted by @JsonInclude(NON_NULL)
                .andExpect(jsonPath("$.items[0].scheduleRule").doesNotExist())
                // sourceSuggestionStateId null → omitted by @JsonInclude(NON_NULL)
                .andExpect(jsonPath("$.items[0].sourceSuggestionStateId").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 3. Tombstoned plans excluded (deleted_at IS NULL)
    // -------------------------------------------------------------------------

    @Test
    void get_tombstonedPlan_notIncluded() throws Exception {
        MedicationPlan live = buildAndSave(userId);
        MedicationPlan dead = buildAndSave(userId);
        // Crypto-shred on tombstone: null nameCipher allowed because deleted_at IS NOT NULL
        dead.setDeletedAt(Instant.now());
        dead.setNameCipher(null);
        planRepo.save(dead);

        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(live.getId().toString()));
    }

    // -------------------------------------------------------------------------
    // 4. IDOR: user A cannot see user B's plans (D7)
    // -------------------------------------------------------------------------

    @Test
    void get_idor_userACannotSeeUserBPlans() throws Exception {
        User userB = new User();
        userB.setEmail("medplan-b@example.com");
        userB.setEmailVerified(true);
        userB = users.save(userB);

        // Seed caller A's OWN plan AND user B's plan — mixed-tenant fixture (true filter proof)
        MedicationPlan aPlan = buildAndSave(userId);
        buildAndSave(userB.getId()); // must NOT appear in A's response

        // Response must contain exactly A's plan and nothing from B
        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(aPlan.getId().toString()));
    }

    // -------------------------------------------------------------------------
    // 5. Unverified email → 403 email_unverified (ADR G-4 RULING 7.4)
    //    auth + email_verified only — no cloud_storage gate on read
    // -------------------------------------------------------------------------

    @Test
    void get_unverifiedEmail_returns403() throws Exception {
        String unverifiedBearer = jwtService.issueAccessToken(userId, false);

        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + unverifiedBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_unverified"));
    }

    // -------------------------------------------------------------------------
    // 6. Unauthenticated → 401
    // -------------------------------------------------------------------------

    @Test
    void get_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/medication-plans"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // 7. Invalid cursor → 400 invalid_cursor
    // -------------------------------------------------------------------------

    @Test
    void get_invalidCursor_returns400() throws Exception {
        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer)
                        .param("cursor", "this-is-not-a-valid-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
    }

    // -------------------------------------------------------------------------
    // 8. Cursor pagination: nextCursor issued; continuation returns the remaining page;
    //    all 3 rows returned across pages (no overlap, no missing)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void get_pagination_nextCursorIssuedAndContinuationWorks() throws Exception {
        MedicationPlan a = buildAndSave(userId);
        MedicationPlan b = buildAndSave(userId);
        MedicationPlan c = buildAndSave(userId);
        Set<String> allIds = Set.of(a.getId().toString(), b.getId().toString(), c.getId().toString());

        // Page 1 (limit=2): 2 items + nextCursor
        MvcResult r1 = mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();

        Map<String, Object> body1 = objectMapper.readValue(
                r1.getResponse().getContentAsString(), Map.class);
        String nextCursor = (String) body1.get("nextCursor");
        assertThat(nextCursor).isNotBlank();

        // Page 2 (using cursor, limit=2): 1 remaining item, no nextCursor
        MvcResult r2 = mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer)
                        .param("cursor", nextCursor)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andReturn();

        // All 3 unique IDs appear across both pages; no overlap, no missing
        Map<String, Object> body2 = objectMapper.readValue(
                r2.getResponse().getContentAsString(), Map.class);
        List<Map<String, Object>> items1 = (List<Map<String, Object>>) body1.get("items");
        List<Map<String, Object>> items2 = (List<Map<String, Object>>) body2.get("items");

        Set<String> combined = new HashSet<>();
        items1.forEach(i -> combined.add((String) i.get("id")));
        items2.forEach(i -> combined.add((String) i.get("id")));

        assertThat(combined).containsExactlyInAnyOrderElementsOf(allIds);
        // No overlap: page1 and page2 share no IDs
        Set<String> page1Ids = new HashSet<>();
        items1.forEach(i -> page1Ids.add((String) i.get("id")));
        items2.forEach(i -> assertThat(page1Ids).doesNotContain((String) i.get("id")));
    }

    // -------------------------------------------------------------------------
    // 9. limit > 500 silently clamped to 500 (spec §A.2 — max 500)
    // -------------------------------------------------------------------------

    @Test
    void get_limitAboveMax_clampedTo500() throws Exception {
        buildAndSave(userId);
        buildAndSave(userId);
        buildAndSave(userId);

        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer)
                        .param("limit", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 10. Identical updatedAt — id DESC tie-break through cursor returns both rows,
    //     no skip, no duplicate (spec §A.2: ORDER BY updated_at DESC, id DESC)
    //
    //     Two plans share the same server-assigned updatedAt (forced via native SQL).
    //     DESC order: higher-id first (page 1 tail). Cursor at higher-id → page 2 starts
    //     with lower-id via tie-break.
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void get_identicalUpdatedAt_idTiebreakThroughCursorReturnsAll() throws Exception {
        // Use known UUIDs so lexicographic order is deterministic
        UUID smallerId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID largerId  = UUID.fromString("20000000-0000-0000-0000-000000000002");

        MedicationPlan planSmaller = buildPlanWith(userId, smallerId);
        MedicationPlan planLarger  = buildPlanWith(userId, largerId);

        // Force both plans to the same updatedAt via native SQL (bypasses @PreUpdate)
        Instant tShared = Instant.parse("2020-06-01T12:00:00Z");
        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tShared))
                .setParameter(2, smallerId)
                .executeUpdate();
        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tShared))
                .setParameter(2, largerId)
                .executeUpdate();
        em.flush();
        em.clear(); // evict L1 cache so the controller sees updated values

        // Page 1 (limit=1): higher-id comes first in (updatedAt DESC, id DESC) order
        MvcResult r1 = mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(largerId.toString()))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();

        Map<String, Object> body1 = objectMapper.readValue(
                r1.getResponse().getContentAsString(), Map.class);
        String nextCursor = (String) body1.get("nextCursor");
        assertThat(nextCursor).isNotBlank();

        // Page 2 using cursor: must return the smaller-id plan — no skip, no duplicate
        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer)
                        .param("cursor", nextCursor)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(smallerId.toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 11. Sub-second updatedAt cursor precision: two plans at different sub-second
    //     updatedAt values within the same second — cursor must encode full Instant
    //     precision so page-2 does not skip the earlier-in-second plan.
    //
    //     Plan A: updatedAt = T.500ms  (appears first in DESC order)
    //     Plan B: updatedAt = T.100ms  (appears second)
    //
    //     Without full-precision cursor the cursor would encode "T.000Z" (truncated to
    //     seconds) and the page-2 predicate (updatedAt < T.000Z) would exclude Plan B
    //     (which is at T.100ms > T.000Z). With full precision cursor encodes T.500Z and
    //     page-2 predicate (updatedAt < T.500Z) correctly includes Plan B at T.100ms.
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void get_subSecondUpdatedAt_cursorPrecision_noRowsSkipped() throws Exception {
        UUID idA = UUID.fromString("a0000000-0000-0000-0000-000000000001");
        UUID idB = UUID.fromString("b0000000-0000-0000-0000-000000000002");

        MedicationPlan planA = buildPlanWith(userId, idA);
        MedicationPlan planB = buildPlanWith(userId, idB);

        // Force sub-second-distinct updatedAt values via native SQL (bypasses @PreUpdate)
        // Plan A at 500ms — higher updatedAt → first in DESC order
        Instant tA = Instant.parse("2026-07-01T09:00:00.500Z");
        // Plan B at 100ms — lower updatedAt → second in DESC order
        Instant tB = Instant.parse("2026-07-01T09:00:00.100Z");

        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tA))
                .setParameter(2, idA)
                .executeUpdate();
        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tB))
                .setParameter(2, idB)
                .executeUpdate();
        em.flush();
        em.clear(); // evict L1 cache

        // Page 1 (limit=1): Plan A (500ms) must come first
        MvcResult r1 = mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(idA.toString()))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();

        Map<String, Object> body1 = objectMapper.readValue(
                r1.getResponse().getContentAsString(), Map.class);
        String nextCursor = (String) body1.get("nextCursor");
        assertThat(nextCursor).isNotBlank();

        // Page 2 using cursor: Plan B (100ms) must appear — must NOT be skipped.
        // Without sub-second precision the cursor would encode T.000Z and the
        // predicate updatedAt < T.000Z would exclude Plan B (at T.100ms > T.000Z).
        mvc.perform(get("/medication-plans")
                        .header("Authorization", "Bearer " + bearer)
                        .param("cursor", nextCursor)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(idB.toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Builds and saves a live plan with name_cipher set (DB CHECK requires it for live rows). */
    private MedicationPlan buildAndSave(UUID ownerId) {
        MedicationPlan p = new MedicationPlan();
        p.setId(UUID.randomUUID());
        p.setUserId(ownerId);
        p.setNameCipher("Folic Acid".getBytes());
        p.setActive(true);
        return planRepo.save(p);
    }

    /** Builds and saves a plan with a specific known UUID. */
    private MedicationPlan buildPlanWith(UUID ownerId, UUID id) {
        MedicationPlan p = new MedicationPlan();
        p.setId(id);
        p.setUserId(ownerId);
        p.setNameCipher("Vitamin D".getBytes());
        p.setActive(true);
        return planRepo.save(p);
    }
}
