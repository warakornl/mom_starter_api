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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for {@code GET /v1/self-logs} (read-only history view, Slice 1 Task 4).
 *
 * <p>Covers (api-contract "Self-logs", spec §A.2, ADR G-4):
 * <ul>
 *   <li><strong>Empty = 200</strong> (not 404) with {@code items:[]} — critical contract invariant</li>
 *   <li>Single log returned with all response fields</li>
 *   <li>{@code metricType} filter: valid value filters correctly; invalid value → 400</li>
 *   <li>{@code from}/{@code to} date-range filter on {@code loggedAt} (floating-civil bucket key)</li>
 *   <li><strong>End-of-day {@code to} boundary</strong>: a row at 23:59:30 is included when
 *       {@code to} = that civil day — enforcing inclusive civil-date bucket semantics (§A.2.3,
 *       backend-reviewer carry-forward). The controller normalises {@code to} to end-of-day.</li>
 *   <li>Tombstoned rows excluded ({@code deleted_at IS NULL})</li>
 *   <li><strong>IDOR</strong>: user A cannot see user B's logs — JWT subject is the only scope</li>
 *   <li>Unverified email → 403 {@code email_unverified} (ADR G-4: auth + email_verified only)</li>
 *   <li>Unauthenticated → 401</li>
 *   <li>Invalid cursor → 400 {@code invalid_cursor}</li>
 *   <li>Cursor pagination: {@code nextCursor} issued; continuation returns next page</li>
 *   <li>Empty result after filter → 200 with empty {@code items:[]}</li>
 * </ul>
 *
 * <p>Security posture (ADR G-4): auth-only + {@code email_verified}. No {@code cloud_storage}
 * gate on this read endpoint. IDOR enforced structurally via JWT subject scope.
 * Ciphertext fields ({@code valueNumeric}/{@code valueNumericSecondary}/{@code valueText}/
 * {@code note}) are returned as opaque Base64 — server never decrypts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class SelfLogMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private SelfLogRepository logs;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    /**
     * ConsentChecker is wired into the sync push path.
     * Mocked here so it does not cause NPE in the Spring context.
     */
    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    @BeforeEach
    void setup() {
        logs.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("selflog-read@example.com");
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
    void get_noLogs_returns200_emptyItems() throws Exception {
        // Contract spec §A.2: empty = 200 {items:[], nextCursor absent} — NOT 404
        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 2. Single log returned with correct fields
    // -------------------------------------------------------------------------

    @Test
    void get_singleLog_returned_withAllFields() throws Exception {
        byte[] numericBytes = "64.2".getBytes();
        String expectedBase64 = Base64.getEncoder().encodeToString(numericBytes);

        SelfLog s = buildAndSave(userId, "weight",
                LocalDateTime.of(2026, 7, 1, 9, 0));
        s.setValueNumeric(numericBytes);
        s.setUnit("kg");
        logs.save(s);

        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(s.getId().toString()))
                .andExpect(jsonPath("$.items[0].metricType").value("weight"))
                .andExpect(jsonPath("$.items[0].unit").value("kg"))
                .andExpect(jsonPath("$.items[0].loggedAt").value("2026-07-01T09:00"))
                .andExpect(jsonPath("$.items[0].valueNumeric").value(expectedBase64))
                .andExpect(jsonPath("$.items[0].version").isNumber())
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].updatedAt").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // 3. metricType filter — valid type filters correctly
    // -------------------------------------------------------------------------

    @Test
    void get_metricTypeFilter_returnsOnlyMatchingType() throws Exception {
        buildAndSave(userId, "weight",         LocalDateTime.of(2026, 7, 1, 8, 0));
        SelfLog bp = buildAndSave(userId, "blood_pressure", LocalDateTime.of(2026, 7, 1, 9, 0));
        buildAndSave(userId, "symptom",        LocalDateTime.of(2026, 7, 1, 10, 0));

        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("metricType", "blood_pressure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(bp.getId().toString()))
                .andExpect(jsonPath("$.items[0].metricType").value("blood_pressure"));
    }

    // -------------------------------------------------------------------------
    // 4. from / to range filter on loggedAt
    // -------------------------------------------------------------------------

    @Test
    void get_rangeFilter_fromAndTo_returnsOnlyMatchingLogs() throws Exception {
        buildAndSave(userId, "weight", LocalDateTime.of(2026, 6, 1, 9, 0));   // before range
        SelfLog inRange = buildAndSave(userId, "weight", LocalDateTime.of(2026, 7, 15, 9, 0));
        buildAndSave(userId, "weight", LocalDateTime.of(2026, 8, 1, 9, 0));   // after range

        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("from", "2026-07-01T00:00")
                        .param("to",   "2026-07-31T23:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(inRange.getId().toString()));
    }

    // -------------------------------------------------------------------------
    // 5. End-of-day 'to' boundary: row at 23:59:30 must be included (spec §A.2.3)
    //
    //    The controller normalises 'to' to end-of-day (toLocalDate().atTime(LocalTime.MAX))
    //    so a row logged at 23:59:30 is INCLUDED when to=YYYY-MM-DDT23:59 (minute precision).
    // -------------------------------------------------------------------------

    @Test
    void get_toBoundary_rowAt_23_59_30_isIncluded_whenToIsEndOfDay() throws Exception {
        // Row at 23:59:30 on July 3 — would be excluded by a strict <= 23:59 comparison.
        // After end-of-day normalisation (23:59:59.999…) it MUST be included.
        SelfLog lateRow = new SelfLog();
        lateRow.setId(UUID.randomUUID());
        lateRow.setUserId(userId);
        lateRow.setMetricType("weight");
        lateRow.setLoggedAt(LocalDateTime.of(2026, 7, 3, 23, 59, 30)); // seconds preserved by DB
        logs.save(lateRow);

        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("from", "2026-07-03T00:00")
                        .param("to",   "2026-07-03T23:59"))   // client sends minute-precision
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(lateRow.getId().toString()));
    }

    // -------------------------------------------------------------------------
    // 6. Tombstoned rows excluded
    // -------------------------------------------------------------------------

    @Test
    void get_tombstonedLog_notIncluded() throws Exception {
        SelfLog live       = buildAndSave(userId, "weight", LocalDateTime.of(2026, 7, 1, 9, 0));
        SelfLog tombstoned = buildAndSave(userId, "weight", LocalDateTime.of(2026, 7, 2, 9, 0));
        tombstoned.setDeletedAt(Instant.now());
        logs.save(tombstoned);

        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(live.getId().toString()));
    }

    // -------------------------------------------------------------------------
    // 7. IDOR: user A cannot see user B's logs
    //    Ownership is enforced by JWT subject — cross-user access is structurally impossible (D7)
    // -------------------------------------------------------------------------

    @Test
    void get_idor_userACannotSeeUserBLogs() throws Exception {
        User userB = new User();
        userB.setEmail("selflog-b@example.com");
        userB.setEmailVerified(true);
        userB = users.save(userB);

        // Log belonging to user B
        buildAndSave(userB.getId(), "weight", LocalDateTime.of(2026, 7, 1, 9, 0));

        // User A's request — must see an empty list
        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    // -------------------------------------------------------------------------
    // 8. Unverified email → 403 email_unverified (ADR G-4: auth + email_verified only)
    // -------------------------------------------------------------------------

    @Test
    void get_unverifiedEmail_returns403() throws Exception {
        String unverifiedBearer = jwtService.issueAccessToken(userId, false);

        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + unverifiedBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_unverified"));
    }

    // -------------------------------------------------------------------------
    // 9. Unauthenticated → 401
    // -------------------------------------------------------------------------

    @Test
    void get_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/self-logs"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // 10. Invalid metricType → 400 validation_error(unknown_metric_type)
    //     Spec §A.2 / §C E2: invalid enum value must not be silently ignored
    // -------------------------------------------------------------------------

    @Test
    void get_invalidMetricType_returns400_unknownMetricType() throws Exception {
        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("metricType", "invalid_value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.details").value("unknown_metric_type"));
    }

    // -------------------------------------------------------------------------
    // 11. Invalid cursor → 400 invalid_cursor
    // -------------------------------------------------------------------------

    @Test
    void get_invalidCursor_returns400() throws Exception {
        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("cursor", "this-is-not-a-valid-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
    }

    // -------------------------------------------------------------------------
    // 12. Cursor pagination: nextCursor issued; continuation returns the next page
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void get_pagination_nextCursorIssuedAndContinuationWorks() throws Exception {
        // Create 3 logs; request limit=2 → page1 has 2, page2 has 1
        SelfLog a = buildAndSave(userId, "weight", LocalDateTime.of(2026, 7, 3, 9, 0));
        SelfLog b = buildAndSave(userId, "weight", LocalDateTime.of(2026, 7, 2, 9, 0));
        SelfLog c = buildAndSave(userId, "weight", LocalDateTime.of(2026, 7, 1, 9, 0));

        // Page 1
        MvcResult r1 = mvc.perform(get("/self-logs")
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

        // Page 1 should start with the newest log (DESC order)
        List<Map<String, Object>> items1 = (List<Map<String, Object>>) body1.get("items");
        assertThat(items1.get(0).get("id")).isEqualTo(a.getId().toString());
        assertThat(items1.get(1).get("id")).isEqualTo(b.getId().toString());

        // Page 2 using the cursor — should return the oldest log only
        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("cursor", nextCursor)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(c.getId().toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 13. Empty result after filter → 200 with empty items:[] (NOT 404)
    // -------------------------------------------------------------------------

    @Test
    void get_filterMatchesNothing_returns200_emptyItems() throws Exception {
        buildAndSave(userId, "weight", LocalDateTime.of(2026, 7, 1, 9, 0));

        // metricType filter that yields no rows
        mvc.perform(get("/self-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("metricType", "lochia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SelfLog buildAndSave(UUID ownerId, String metricType, LocalDateTime loggedAt) {
        SelfLog s = new SelfLog();
        s.setId(UUID.randomUUID());
        s.setUserId(ownerId);
        s.setMetricType(metricType);
        s.setLoggedAt(loggedAt);
        return logs.save(s);
    }
}
