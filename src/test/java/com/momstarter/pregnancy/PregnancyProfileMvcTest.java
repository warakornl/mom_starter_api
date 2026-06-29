package com.momstarter.pregnancy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
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
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice integration test for GET/PUT /pregnancy-profile.
 *
 * <p>Covers every status code in the contract:
 * GET 404, GET 200 (with X-Client-Date snapshot), PUT create 201, PUT create via currentWeek 201,
 * PUT update 200 (+If-Match), PUT 428 missing If-Match, PUT 409 stale, PUT no-op 200,
 * PUT XOR 422 (both provided), PUT XOR 422 (neither), PUT EDD-window 422.
 *
 * <p>Uses @Transactional to roll back the DB after each test (H2 in PostgreSQL mode).
 * Follows the same pattern as AuthLoginMvcTest / AuthLogoutMvcTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class PregnancyProfileMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PregnancyProfileRepository profiles;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ObjectMapper objectMapper;

    /** Replaces AlwaysGrantedConsentChecker so individual tests can override consent behaviour. */
    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;

    /** A fixed civil-date "today" sent as X-Client-Date in every request. */
    private static final String CLIENT_DATE = "2026-06-29";
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 29);

    @BeforeEach
    void seed() {
        profiles.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("mom-profile@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        bearer = jwtService.issueAccessToken(user.getId(), true);
        // Default: consent always granted so existing PUT tests are unaffected.
        // Individual tests override this stub when they need to test the denial path.
        when(consentChecker.isGranted(any(), any())).thenReturn(true);
    }

    // =========================================================================
    // GET /pregnancy-profile
    // =========================================================================

    @Test
    void get_noProfileYet_returns404() throws Exception {
        mvc.perform(get("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void get_withProfile_returns200AndDerivedSnapshot() throws Exception {
        // Create the profile via PUT first
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-28\"}"))
                .andExpect(status().isCreated());

        // daysPregnant = 280 - daysUntilEdd = 280 - (2027-03-28 - 2026-06-29) in days
        // = 280 - 272 = 8 -> week=1 day=1 stage=T1
        mvc.perform(get("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edd").value("2027-03-28"))
                .andExpect(jsonPath("$.lifecycle").value("pregnant"))
                .andExpect(jsonPath("$.currentStage").value("T1"))
                .andExpect(jsonPath("$.gestationalWeek").isNumber())
                .andExpect(jsonPath("$.gestationalDay").isNumber())
                .andExpect(jsonPath("$.daysRemaining").isNumber())
                .andExpect(jsonPath("$.progress").isNumber())
                .andExpect(jsonPath("$.deliveryWindowActive").value(false))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void get_xClientDateUsedForSnapshot() throws Exception {
        // Store EDD = 2026-09-25 (today = 2026-06-29 → daysUntilEdd=88 → daysPregnant=192 → week=27 T2)
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2026-09-25\"}"))
                .andExpect(status().isCreated());

        // Re-GET with a different X-Client-Date to confirm snapshot uses client date
        String laterDate = "2026-08-01"; // daysUntilEdd = 2026-09-25 - 2026-08-01 = 55 → daysPregnant=225 → week=32 T3
        MvcResult result = mvc.perform(get("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", laterDate))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // week=32 → currentStage should be T3 (not T2 as when using original date)
        assertThat(body).contains("\"currentStage\":\"T3\"");
    }

    @Test
    void get_requiresAuthentication() throws Exception {
        mvc.perform(get("/pregnancy-profile")
                        .header("X-Client-Date", CLIENT_DATE))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // PUT /pregnancy-profile — create (201)
    // =========================================================================

    @Test
    void put_createWithEdd_returns201() throws Exception {
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-15\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.edd").value("2027-03-15"))
                .andExpect(jsonPath("$.eddBasis").value("due_date"))
                .andExpect(jsonPath("$.lifecycle").value("pregnant"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.currentStage").isString())
                .andExpect(jsonPath("$.gestationalWeek").isNumber())
                .andExpect(jsonPath("$.progress").isNumber())
                .andExpect(jsonPath("$.deliveryWindowActive").isBoolean());
    }

    @Test
    void put_createWithCurrentWeek_returns201AndComputedEdd() throws Exception {
        // currentWeek=10 → edd = today + (280 - 70) = today + 210 = 2026-06-29 + 210 = 2027-01-25
        LocalDate expectedEdd = TODAY.plusDays(280L - 10 * 7);

        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentWeek\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.edd").value(expectedEdd.toString()))
                .andExpect(jsonPath("$.eddBasis").value("current_week"))
                .andExpect(jsonPath("$.gestationalWeek").value(10))
                .andExpect(jsonPath("$.gestationalDay").value(0));
    }

    @Test
    void put_createDoesNotRequireIfMatch() throws Exception {
        // No If-Match header on create → must succeed (201), not 428
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-04-01\"}"))
                .andExpect(status().isCreated());
    }

    // =========================================================================
    // PUT /pregnancy-profile — update (200)
    // =========================================================================

    @Test
    void put_updateWithValidIfMatch_returns200() throws Exception {
        // Create
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-01\"}"))
                .andExpect(status().isCreated());

        // Update with If-Match: "0" (version starts at 0)
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-04-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edd").value("2027-04-10"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void put_updateMissingIfMatch_returns428() throws Exception {
        // Create
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-01\"}"))
                .andExpect(status().isCreated());

        // Update WITHOUT If-Match → 428
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-04-10\"}"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("precondition_required"));
    }

    @Test
    void put_updateStaleIfMatch_returns409WithCurrentProfile() throws Exception {
        // Create
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-01\"}"))
                .andExpect(status().isCreated());

        // Update with If-Match: "99" (wrong version) → 409
        MvcResult result = mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"99\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-04-10\"}"))
                .andExpect(status().isConflict())
                .andReturn();

        // Body must be the current authoritative profile, not a Problem
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"edd\":\"2027-03-01\"");   // original EDD
        assertThat(body).contains("\"version\":0");            // version unchanged
    }

    @Test
    void put_noOpSameEdd_returns200WithoutVersionBump() throws Exception {
        // Create
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-01\"}"))
                .andExpect(status().isCreated());

        // PUT with the SAME edd and correct If-Match → 200, version stays 0
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));   // NOT bumped (OQ-9)
    }

    // =========================================================================
    // PUT /pregnancy-profile — 422 XOR validation
    // =========================================================================

    @Test
    void put_bothEddAndCurrentWeekProvided_returns422() throws Exception {
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-01\",\"currentWeek\":10}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void put_neitherEddNorCurrentWeekProvided_returns422() throws Exception {
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void put_nullBody_returns422() throws Exception {
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    // =========================================================================
    // PUT /pregnancy-profile — 422 EDD window guard (OQ-6)
    // =========================================================================

    @Test
    void put_eddTooFarInFuture_returns422() throws Exception {
        // edd = today + 309 days → outside max window of today+308d
        String tooFar = TODAY.plusDays(309).toString();
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"" + tooFar + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void put_eddTooFarInPast_returns422() throws Exception {
        // edd = today - 29 days → outside min window of today-28d
        String tooOld = TODAY.minusDays(29).toString();
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"" + tooOld + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void put_eddAtWindowBoundaries_succeeds() throws Exception {
        // Exactly at clientDate - 28d → should succeed
        String atMinBoundary = TODAY.minusDays(28).toString();
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"" + atMinBoundary + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void put_eddAtMaxWindowBoundary_succeeds() throws Exception {
        // Exactly at clientDate + 308d → should succeed
        String atMaxBoundary = TODAY.plusDays(308).toString();
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"" + atMaxBoundary + "\"}"))
                .andExpect(status().isCreated());
    }

    // =========================================================================
    // PUT /pregnancy-profile — authentication required
    // =========================================================================

    @Test
    void put_requiresAuthentication() throws Exception {
        mvc.perform(put("/pregnancy-profile")
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-01\"}"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // PUT /pregnancy-profile — currentWeek EDD window (via computed EDD)
    // =========================================================================

    @Test
    void put_currentWeekOutsideWindow_returns422() throws Exception {
        // currentWeek=44 → edd = today + (280 - 308) = today - 28 → exactly at boundary (valid)
        // currentWeek=45 → edd = today + (280 - 315) = today - 35 → outside (-28 min)
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentWeek\":45}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void put_currentWeekAtMaxBoundary_returns201() throws Exception {
        // api-contract: currentWeek range is 1–42 (inclusive).
        // currentWeek=42 → edd = today + (280 - 294) = today - 14 → within EDD window (valid → 201)
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentWeek\":42}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eddBasis").value("current_week"));
    }

    @Test
    void put_currentWeek43_returns422() throws Exception {
        // currentWeek=43 is out of the valid range (1–42) → 422 validation_error (fix #2)
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentWeek\":43}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    // =========================================================================
    // Fix #1 — Resurrect tombstone
    // =========================================================================

    @Test
    void put_resurrectTombstone_returns201() throws Exception {
        // Step 1: create a live profile
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-15\"}"))
                .andExpect(status().isCreated());

        // Step 2: soft-delete it directly via repo (simulating a GDPR/delete operation)
        PregnancyProfile tombstone = profiles.findByUserId(user.getId()).orElseThrow();
        tombstone.setDeletedAt(Instant.now());
        profiles.saveAndFlush(tombstone);

        // Step 3: PUT again — must resurrect (201), not throw 500 unique-constraint violation
        // EDD 2027-04-15 = today + 290 days, within the 308-day max window
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-04-15\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.edd").value("2027-04-15"));
    }

    // =========================================================================
    // Fix #3 — 403 consent_required must carry details: "general_health"
    // =========================================================================

    @Test
    void put_consentDenied_returns403WithDetails() throws Exception {
        // Override default stub: consent is denied for general_health
        when(consentChecker.isGranted(any(), eq("general_health"))).thenReturn(false);

        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-15\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("consent_required"))
                .andExpect(jsonPath("$.details").value("general_health"));
    }

    // =========================================================================
    // Fix #4 — If-Match present but malformed → 412 (not 428)
    // =========================================================================

    @Test
    void put_ifMatchMalformed_returns412() throws Exception {
        // Create a live profile first so the update path is reached
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-15\"}"))
                .andExpect(status().isCreated());

        // Send a malformed If-Match value ("abc" cannot be parsed as a version number).
        // EDD 2027-04-15 = today + 290 days, within the 308-day max window
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"abc\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-04-15\"}"))
                .andExpect(status().is(412))
                .andExpect(jsonPath("$.code").value("precondition_failed"));
    }

    // =========================================================================
    // Security — cross-user isolation (no IDOR on GET)
    // =========================================================================

    @Test
    void get_crossUserIsolation_returns404() throws Exception {
        // Create user A's profile
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"2027-03-15\"}"))
                .andExpect(status().isCreated());

        // Create user B — a completely different account
        User userB = new User();
        userB.setEmail("user-b@example.com");
        userB.setEmailVerified(true);
        userB = users.save(userB);
        String bearerB = jwtService.issueAccessToken(userB.getId(), true);

        // User B must NOT be able to read user A's profile → 404
        mvc.perform(get("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearerB)
                        .header("X-Client-Date", CLIENT_DATE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }
}
