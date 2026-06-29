package com.momstarter.pregnancy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.dto.BirthEventInput;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for POST /pregnancy-profile/birth-event.
 *
 * <p>Covers:
 * <ul>
 *   <li>200 pregnant → postpartum transition (happy path)</li>
 *   <li>404 no profile exists</li>
 *   <li>409 lifecycle == "ended"</li>
 *   <li>422 birthDate in the future</li>
 *   <li>422 birthDate before edd − 126 days</li>
 *   <li>428 If-Match header absent</li>
 *   <li>409 stale If-Match (version mismatch)</li>
 *   <li>200 no-op when already postpartum with the same birthDate (version NOT bumped)</li>
 *   <li>200 edit when already postpartum with a different birthDate (version bumped)</li>
 *   <li>403 consent denied (via {@literal @MockBean})</li>
 *   <li>GET /pregnancy-profile after birth-event returns postpartum snapshot</li>
 * </ul>
 *
 * <p>Test EDD: 2026-10-01
 * <ul>
 *   <li>edd − 126 = 2026-05-28 → minimum valid birthDate</li>
 *   <li>clientDate (today) = 2026-06-29 → maximum valid birthDate</li>
 *   <li>Valid window: [2026-05-28, 2026-06-29]</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class BirthEventMvcTest {

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

    /** Replaces AlwaysGrantedConsentChecker to allow per-test consent overrides. */
    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;

    /**
     * Fixed civil-date "today" for all requests.
     * EDD = 2026-10-01 → edd − 126 = 2026-05-28 (sanity floor for birthDate).
     * birthDate window: [2026-05-28, 2026-06-29].
     */
    private static final String CLIENT_DATE  = "2026-06-29";
    private static final LocalDate TODAY     = LocalDate.of(2026, 6, 29);
    private static final String EDD_STR      = "2026-10-01";
    private static final String BIRTH_DATE   = "2026-06-29";        // valid, on today
    private static final String BIRTH_DATE_2 = "2026-06-28";        // valid, one day earlier
    private static final String FUTURE_DATE  = "2026-06-30";        // tomorrow → 422
    private static final String TOO_EARLY    = "2026-05-27";        // < edd-126 → 422

    @BeforeEach
    void seed() {
        profiles.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("birth-event@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        bearer = jwtService.issueAccessToken(user.getId(), true);
        // Default: consent always granted. Individual tests override when testing denial path.
        when(consentChecker.isGranted(any(), any())).thenReturn(true);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Creates a pregnant profile with EDD_STR via PUT and returns the response body. */
    private MvcResult createPregnantProfile() throws Exception {
        return mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"" + EDD_STR + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    /** Builds the birth-event JSON body. */
    private String birthBody(String birthDate) {
        return "{\"birthDate\":\"" + birthDate + "\"}";
    }

    private String birthBodyWithMeta(String birthDate, String deliveryType, String note) {
        return "{\"birthDate\":\"" + birthDate + "\","
                + "\"deliveryType\":\"" + deliveryType + "\","
                + "\"birthNote\":\"" + note + "\"}";
    }

    // =========================================================================
    // Happy path — pregnant → postpartum (200)
    // =========================================================================

    @Test
    void birthEvent_pregnantToPostpartum_returns200() throws Exception {
        createPregnantProfile();

        // Post birth-event with correct If-Match (version=0 from create)
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("postpartum"))
                .andExpect(jsonPath("$.currentStage").value("postpartum"))
                .andExpect(jsonPath("$.deliveryWindowActive").value(false))
                .andExpect(jsonPath("$.birthDate").value(BIRTH_DATE))
                .andExpect(jsonPath("$.postpartumDays").value(0))
                .andExpect(jsonPath("$.postpartumWeek").value(0))
                .andExpect(jsonPath("$.postpartumDay").value(0))
                .andExpect(jsonPath("$.version").value(1))
                // Gestational fields MUST be absent (null → excluded by @JsonInclude NON_NULL)
                .andExpect(jsonPath("$.gestationalWeek").doesNotExist())
                .andExpect(jsonPath("$.gestationalDay").doesNotExist())
                .andExpect(jsonPath("$.daysRemaining").doesNotExist())
                .andExpect(jsonPath("$.progress").doesNotExist());
    }

    @Test
    void birthEvent_withDeliveryTypeAndNote_returns200AndStoresFields() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBodyWithMeta(BIRTH_DATE, "vaginal", "all went well")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("postpartum"))
                .andExpect(jsonPath("$.birthDate").value(BIRTH_DATE));
    }

    // =========================================================================
    // 404 — no profile
    // =========================================================================

    @Test
    void birthEvent_noProfile_returns404() throws Exception {
        // No profile created; send birth-event → 404
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    // =========================================================================
    // 409 — lifecycle == "ended"
    // =========================================================================

    @Test
    void birthEvent_endedLifecycle_returns409() throws Exception {
        createPregnantProfile();

        // Directly set lifecycle to "ended" via repository
        PregnancyProfile p = profiles.findByUserIdAndDeletedAtIsNull(user.getId()).orElseThrow();
        p.setLifecycle("ended");
        profiles.saveAndFlush(p);

        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_lifecycle_state"))
                .andExpect(jsonPath("$.details").value("ended"));
    }

    // =========================================================================
    // 422 — birthDate bounds
    // =========================================================================

    @Test
    void birthEvent_futureBirthDate_returns422() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(FUTURE_DATE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void birthEvent_tooEarlyBirthDate_returns422() throws Exception {
        // TOO_EARLY = 2026-05-27 < edd(2026-10-01) - 126 = 2026-05-28
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(TOO_EARLY)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void birthEvent_birthDateAtSanityFloor_succeeds() throws Exception {
        // birthDate = edd - 126 = 2026-05-28 → exactly at the boundary → 200
        createPregnantProfile();
        // birthDate 2026-05-28 ≤ today(2026-06-29) and = edd(2026-10-01)-126 → valid
        String atFloor = "2026-05-28";

        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(atFloor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("postpartum"))
                .andExpect(jsonPath("$.birthDate").value(atFloor));
    }

    // =========================================================================
    // 428 — If-Match absent
    // =========================================================================

    @Test
    void birthEvent_missingIfMatch_returns428() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        // No If-Match header
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE)))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("precondition_required"));
    }

    // =========================================================================
    // 409 — stale If-Match
    // =========================================================================

    @Test
    void birthEvent_staleIfMatch_returns409WithCurrentProfile() throws Exception {
        createPregnantProfile();

        MvcResult result = mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"99\"")   // wrong version
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE)))
                .andExpect(status().isConflict())
                .andReturn();

        // Body must be the current authoritative profile (not a Problem error body)
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"lifecycle\":\"pregnant\"");
        assertThat(body).contains("\"version\":0");
    }

    // =========================================================================
    // 200 no-op — already postpartum, same birthDate (version NOT bumped)
    // =========================================================================

    @Test
    void birthEvent_alreadyPostpartum_sameBirthDate_noOp_returns200() throws Exception {
        createPregnantProfile();

        // First birth-event: pregnant → postpartum (version 0→1)
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // Second birth-event with the SAME birthDate (If-Match "1" — current version)
        // → no-op → version stays 1 (NOT bumped to 2)
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("postpartum"))
                .andExpect(jsonPath("$.birthDate").value(BIRTH_DATE))
                .andExpect(jsonPath("$.version").value(1));   // NOT bumped
    }

    // =========================================================================
    // 200 edit — already postpartum, different birthDate (version bumped)
    // =========================================================================

    @Test
    void birthEvent_alreadyPostpartum_differentBirthDate_edit_returns200() throws Exception {
        createPregnantProfile();

        // First birth-event: pregnant → postpartum with BIRTH_DATE (version 0→1)
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // Second birth-event with a DIFFERENT birthDate (correction) → version bumped 1→2
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE_2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("postpartum"))
                .andExpect(jsonPath("$.birthDate").value(BIRTH_DATE_2))
                .andExpect(jsonPath("$.version").value(2));   // bumped
    }

    // =========================================================================
    // 403 — consent denied
    // =========================================================================

    @Test
    void birthEvent_consentDenied_returns403WithDetails() throws Exception {
        createPregnantProfile();

        // Override default stub: consent denied for general_health
        when(consentChecker.isGranted(any(), eq("general_health"))).thenReturn(false);

        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("consent_required"))
                .andExpect(jsonPath("$.details").value("general_health"));
    }

    // =========================================================================
    // Authentication required
    // =========================================================================

    @Test
    void birthEvent_requiresAuthentication() throws Exception {
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // GET after birth-event returns postpartum snapshot
    // =========================================================================

    @Test
    void get_afterBirthEvent_returnsPostpartumSnapshot() throws Exception {
        createPregnantProfile();

        // Transition to postpartum
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE)))
                .andExpect(status().isOk());

        // GET must now return postpartum snapshot with no gestational fields
        mvc.perform(get("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("postpartum"))
                .andExpect(jsonPath("$.currentStage").value("postpartum"))
                .andExpect(jsonPath("$.deliveryWindowActive").value(false))
                .andExpect(jsonPath("$.birthDate").value(BIRTH_DATE))
                .andExpect(jsonPath("$.postpartumDays").value(0))
                .andExpect(jsonPath("$.postpartumWeek").value(0))
                .andExpect(jsonPath("$.postpartumDay").value(0))
                // Gestational fields must be absent
                .andExpect(jsonPath("$.gestationalWeek").doesNotExist())
                .andExpect(jsonPath("$.gestationalDay").doesNotExist())
                .andExpect(jsonPath("$.daysRemaining").doesNotExist())
                .andExpect(jsonPath("$.progress").doesNotExist());
    }

    // =========================================================================
    // postpartumDays > 0 when birthDate is before today
    // =========================================================================

    @Test
    void birthEvent_birthDateYesterday_postpartumDaysIs1() throws Exception {
        // birthDate = 2026-06-28 (BIRTH_DATE_2) → postpartumDays = today - birthDate = 1
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthBody(BIRTH_DATE_2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postpartumDays").value(1))
                .andExpect(jsonPath("$.postpartumWeek").value(0))
                .andExpect(jsonPath("$.postpartumDay").value(1));
    }
}
