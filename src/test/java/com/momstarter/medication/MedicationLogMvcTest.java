package com.momstarter.medication;

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
 * MVC integration tests for {@code GET /v1/medication-logs} (read-only view, Slice 2 Task 4).
 *
 * <p>Covers (spec §A.3 / ADR G-4 RULING 7.4):
 * <ul>
 *   <li><strong>Empty = 200</strong> (not 404) with {@code items:[], nextCursor absent}</li>
 *   <li>Single log with all fields: note as Base64, status, occurrenceTime (civil), loggedAt (Instant), medicationPlanId</li>
 *   <li>{@code from}/{@code to} date-range filter on {@code occurrenceTime}</li>
 *   <li><strong>Inclusive civil-date bounds (§A.3):</strong>
 *       {@code from} normalised to start-of-day; {@code to} normalised to end-of-day (LocalTime.MAX).
 *       A row at 23:59:30 IS included when {@code to} is the same civil day (end-of-day bound).
 *       A row at 06:00 IS included when {@code from} is 08:00 on the same civil day (start-of-day bound).</li>
 *   <li>Tombstoned rows excluded ({@code deleted_at IS NULL})</li>
 *   <li><strong>IDOR</strong>: JWT subject is the only ownership scope (D7)</li>
 *   <li>Unverified email → 403 {@code email_unverified} (ADR G-4 RULING 7.4)</li>
 *   <li>Unauthenticated → 401</li>
 *   <li>Invalid cursor → 400 {@code invalid_cursor}</li>
 *   <li>Cursor pagination: {@code nextCursor} issued; continuation returns next page</li>
 *   <li><strong>Sub-minute cursor precision</strong>: cursor encodes {@code occurrenceTime} with
 *       full ISO precision so continuation never skips rows within the same minute</li>
 *   <li>limit &gt; 500 silently clamped to 500</li>
 *   <li>Empty after filter → 200 {items:[]}</li>
 *   <li><strong>Identical occurrenceTime tie-break</strong>: {@code id DESC} tie-break through
 *       cursor returns all rows, no skip, no duplicate</li>
 * </ul>
 *
 * <p>Security: auth + {@code email_verified} only — NO {@code cloud_storage} gate on read
 * (ADR G-4 RULING 7.4). Plan-agnostic (returns all logs for the user regardless of plan linkage).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class MedicationLogMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private MedicationPlanRepository planRepo;
    @Autowired private MedicationLogRepository logRepo;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

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
        user.setEmail("medlog-read@example.com");
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
        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 2. Single log returned with all fields
    // -------------------------------------------------------------------------

    @Test
    void get_singleLog_returned_withAllFields() throws Exception {
        byte[] noteBytes = "Take with food".getBytes();
        String expectedNoteBase64 = Base64.getEncoder().encodeToString(noteBytes);
        LocalDateTime occTime = LocalDateTime.of(2026, 7, 3, 9, 0);

        MedicationLog l = new MedicationLog();
        l.setId(UUID.randomUUID());
        l.setUserId(userId);
        l.setOccurrenceTime(occTime);
        l.setStatus("taken");
        l.setNoteCipher(noteBytes);
        logRepo.save(l);

        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(l.getId().toString()))
                .andExpect(jsonPath("$.items[0].occurrenceTime").value("2026-07-03T09:00"))
                .andExpect(jsonPath("$.items[0].status").value("taken"))
                .andExpect(jsonPath("$.items[0].loggedAt").isNotEmpty())  // Instant, UTC
                .andExpect(jsonPath("$.items[0].note").value(expectedNoteBase64))
                .andExpect(jsonPath("$.items[0].version").isNumber())
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].updatedAt").isNotEmpty())
                // ad-hoc log: medicationPlanId absent (NON_NULL → omitted when null)
                .andExpect(jsonPath("$.items[0].medicationPlanId").doesNotExist())
                // internal-only fields must NOT be present
                .andExpect(jsonPath("$.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.items[0].clientId").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 3. from / to range filter on occurrenceTime
    // -------------------------------------------------------------------------

    @Test
    void get_rangeFilter_fromAndTo_returnsOnlyMatchingLogs() throws Exception {
        buildAndSave(userId, LocalDateTime.of(2026, 6, 1, 9, 0));   // before range
        MedicationLog inRange = buildAndSave(userId, LocalDateTime.of(2026, 7, 15, 9, 0));
        buildAndSave(userId, LocalDateTime.of(2026, 8, 1, 9, 0));   // after range

        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("from", "2026-07-01T00:00")
                        .param("to",   "2026-07-31T23:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(inRange.getId().toString()));
    }

    // -------------------------------------------------------------------------
    // 4. End-of-day 'to' boundary: row at 23:59:30 must be included (spec §A.3)
    //
    //    The controller normalises 'to' to end-of-day (toLocalDate().atTime(LocalTime.MAX))
    //    so a row with occurrenceTime=23:59:30 is INCLUDED when to=YYYY-MM-DDT23:59.
    //    Without normalisation the strict <= 23:59 comparison excludes this row.
    // -------------------------------------------------------------------------

    @Test
    void get_toBoundary_rowAt_23_59_30_isIncluded_whenToIsEndOfDay() throws Exception {
        // Row at 23:59:30 on July 3 — would be excluded by strict <= 23:59 comparison.
        // After end-of-day normalisation (23:59:59.999…) it MUST be included.
        MedicationLog lateRow = new MedicationLog();
        lateRow.setId(UUID.randomUUID());
        lateRow.setUserId(userId);
        lateRow.setOccurrenceTime(LocalDateTime.of(2026, 7, 3, 23, 59, 30));
        lateRow.setStatus("taken");
        logRepo.save(lateRow);

        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("from", "2026-07-03T00:00")
                        .param("to",   "2026-07-03T23:59"))  // client sends minute-precision
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(lateRow.getId().toString()));
    }

    // -------------------------------------------------------------------------
    // 5. Symmetric civil 'from' bound: mid-day 'from' still includes earlier same-civil-day row
    //    (spec §A.3 — inclusive civil bounds; mirrors SelfLog Finding 1)
    //
    //    'from' is normalised to start-of-day so a row at 06:00 on the same civil day
    //    is INCLUDED when from=2026-07-01T08:00. Without normalisation the strict
    //    >= 08:00 comparison excludes the 06:00 row.
    // -------------------------------------------------------------------------

    @Test
    void get_fromMidDay_includesEarlierSameCivilDayRow() throws Exception {
        MedicationLog earlyRow = buildAndSave(userId, LocalDateTime.of(2026, 7, 1, 6, 0));
        buildAndSave(userId, LocalDateTime.of(2026, 8, 1, 9, 0)); // outside range (after to)

        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("from", "2026-07-01T08:00")  // mid-day — same civil day as 06:00
                        .param("to",   "2026-07-31T23:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(earlyRow.getId().toString()));
    }

    // -------------------------------------------------------------------------
    // 6. Tombstoned rows excluded (deleted_at IS NULL)
    // -------------------------------------------------------------------------

    @Test
    void get_tombstonedLog_notIncluded() throws Exception {
        MedicationLog live       = buildAndSave(userId, LocalDateTime.of(2026, 7, 1, 9, 0));
        MedicationLog tombstoned = buildAndSave(userId, LocalDateTime.of(2026, 7, 2, 9, 0));
        // Crypto-shred on tombstone: null noteCipher allowed
        tombstoned.setDeletedAt(Instant.now());
        tombstoned.setNoteCipher(null);
        logRepo.save(tombstoned);

        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(live.getId().toString()));
    }

    // -------------------------------------------------------------------------
    // 7. IDOR: user A cannot see user B's logs (D7)
    // -------------------------------------------------------------------------

    @Test
    void get_idor_userACannotSeeUserBLogs() throws Exception {
        User userB = new User();
        userB.setEmail("medlog-b@example.com");
        userB.setEmailVerified(true);
        userB = users.save(userB);

        // Log belonging to user B — must not appear in user A's response
        buildAndSave(userB.getId(), LocalDateTime.of(2026, 7, 1, 9, 0));

        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    // -------------------------------------------------------------------------
    // 8. Unverified email → 403 email_unverified (ADR G-4 RULING 7.4)
    // -------------------------------------------------------------------------

    @Test
    void get_unverifiedEmail_returns403() throws Exception {
        String unverifiedBearer = jwtService.issueAccessToken(userId, false);

        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + unverifiedBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_unverified"));
    }

    // -------------------------------------------------------------------------
    // 9. Unauthenticated → 401
    // -------------------------------------------------------------------------

    @Test
    void get_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/medication-logs"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // 10. Invalid cursor → 400 invalid_cursor
    // -------------------------------------------------------------------------

    @Test
    void get_invalidCursor_returns400() throws Exception {
        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("cursor", "this-is-not-a-valid-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
    }

    // -------------------------------------------------------------------------
    // 11. Cursor pagination: nextCursor issued; continuation returns the next page
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void get_pagination_nextCursorIssuedAndContinuationWorks() throws Exception {
        // 3 logs at distinct occurrenceTimes; ordered (occurrenceTime DESC, id DESC)
        MedicationLog a = buildAndSave(userId, LocalDateTime.of(2026, 7, 3, 9, 0));
        MedicationLog b = buildAndSave(userId, LocalDateTime.of(2026, 7, 2, 9, 0));
        MedicationLog c = buildAndSave(userId, LocalDateTime.of(2026, 7, 1, 9, 0));

        // Page 1 (limit=2): 2 most-recent logs (a, b) + nextCursor
        MvcResult r1 = mvc.perform(get("/medication-logs")
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

        List<Map<String, Object>> items1 = (List<Map<String, Object>>) body1.get("items");
        // descending: newest first
        assertThat(items1.get(0).get("id")).isEqualTo(a.getId().toString());
        assertThat(items1.get(1).get("id")).isEqualTo(b.getId().toString());

        // Page 2 using cursor: returns oldest log only, no nextCursor
        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("cursor", nextCursor)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(c.getId().toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 12. Sub-minute cursor precision: two rows with different sub-minute occurrenceTime
    //     in the same minute across a page boundary (limit=1) are both returned,
    //     none skipped (mirrors SelfLog Finding 2)
    //
    //     Row A at 09:00:30, Row B at 09:00:15. Cursor for Row A must encode the full
    //     precision "09:00:30" so page-2 query uses occurrenceTime < 09:00:30.
    //     Without full-precision cursor the cursor encodes "09:00" (minute-truncated),
    //     page-2 query uses < 09:00:00, and Row B (at 09:00:15 > 09:00:00) is SKIPPED.
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void get_subMinuteCursorPrecision_noRowsSkipped() throws Exception {
        // Row A at 09:00:30 — higher seconds → appears first in occurrenceTime DESC order
        MedicationLog rowA = new MedicationLog();
        rowA.setId(UUID.randomUUID());
        rowA.setUserId(userId);
        rowA.setOccurrenceTime(LocalDateTime.of(2026, 7, 1, 9, 0, 30)); // 09:00:30
        rowA.setStatus("taken");
        logRepo.save(rowA);

        // Row B at 09:00:15 — appears second in occurrenceTime DESC order
        MedicationLog rowB = new MedicationLog();
        rowB.setId(UUID.randomUUID());
        rowB.setUserId(userId);
        rowB.setOccurrenceTime(LocalDateTime.of(2026, 7, 1, 9, 0, 15)); // 09:00:15
        rowB.setStatus("missed");
        logRepo.save(rowB);

        // Page 1 (limit=1): must return Row A (higher seconds → first in DESC)
        MvcResult r1 = mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(rowA.getId().toString()))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();

        Map<String, Object> body1 = objectMapper.readValue(
                r1.getResponse().getContentAsString(), Map.class);
        String nextCursor = (String) body1.get("nextCursor");
        assertThat(nextCursor).isNotBlank();

        // Page 2 using cursor — must return Row B (must NOT be skipped).
        // Without full-precision cursor: cursor encodes "09:00" → page-2 predicate uses
        // occurrenceTime < 09:00:00 → Row B at 09:00:15 is OUTSIDE window → skipped.
        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("cursor", nextCursor)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(rowB.getId().toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 13. limit > 500 silently clamped to 500 (spec §A.3 — max 500)
    // -------------------------------------------------------------------------

    @Test
    void get_limitAboveMax_clampedTo500() throws Exception {
        buildAndSave(userId, LocalDateTime.of(2026, 7, 1, 9, 0));
        buildAndSave(userId, LocalDateTime.of(2026, 7, 2, 9, 0));
        buildAndSave(userId, LocalDateTime.of(2026, 7, 3, 9, 0));

        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("limit", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 14. Empty result after filter → 200 with empty items:[] (NOT 404)
    // -------------------------------------------------------------------------

    @Test
    void get_filterMatchesNothing_returns200_emptyItems() throws Exception {
        buildAndSave(userId, LocalDateTime.of(2026, 7, 1, 9, 0));

        // Date range that yields no rows
        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("from", "2025-01-01T00:00")
                        .param("to",   "2025-12-31T23:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    // -------------------------------------------------------------------------
    // 15. Identical occurrenceTime — id DESC tie-break through cursor returns both rows,
    //     no skip, no duplicate (spec §A.3: ORDER BY occurrenceTime DESC, id DESC)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void get_identicalOccurrenceTime_idTiebreakThroughCursorReturnsAll() throws Exception {
        LocalDateTime sameTime = LocalDateTime.of(2026, 7, 1, 9, 0);
        UUID smallerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID largerId  = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        MedicationLog logSmaller = new MedicationLog();
        logSmaller.setId(smallerId);
        logSmaller.setUserId(userId);
        logSmaller.setOccurrenceTime(sameTime);
        logSmaller.setStatus("taken");
        logRepo.save(logSmaller);

        MedicationLog logLarger = new MedicationLog();
        logLarger.setId(largerId);
        logLarger.setUserId(userId);
        logLarger.setOccurrenceTime(sameTime);
        logLarger.setStatus("missed");
        logRepo.save(logLarger);

        // Page 1 (limit=1): larger-id comes first in (occurrenceTime DESC, id DESC) order
        MvcResult r1 = mvc.perform(get("/medication-logs")
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

        // Page 2 using cursor: must return the smaller-id log — no skip, no duplicate
        mvc.perform(get("/medication-logs")
                        .header("Authorization", "Bearer " + bearer)
                        .param("cursor", nextCursor)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(smallerId.toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Builds and saves an ad-hoc (plan-agnostic) log with the given occurrenceTime. */
    private MedicationLog buildAndSave(UUID ownerId, LocalDateTime occurrenceTime) {
        MedicationLog l = new MedicationLog();
        l.setId(UUID.randomUUID());
        l.setUserId(ownerId);
        l.setOccurrenceTime(occurrenceTime);
        l.setStatus("taken");
        return logRepo.save(l);
    }
}
