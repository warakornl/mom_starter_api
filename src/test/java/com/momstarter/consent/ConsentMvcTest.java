package com.momstarter.consent;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for {@code POST /account/consents} and
 * {@code GET /account/consents}.
 *
 * <p>Runs against H2 (test profile, Flyway-migrated schema) with
 * {@code AlwaysGrantedConsentChecker} as the default bean — confirming that the
 * consent endpoint itself is NOT gated by any consent checker (ungated contract).
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>POST grant → 201 with correct response shape.</li>
 *   <li>POST withdrawal → 201 (granted=false).</li>
 *   <li>POST missing required field → 422 validation_error.</li>
 *   <li>POST invalid consentType → 422 validation_error.</li>
 *   <li>POST without auth → 401 unauthorized.</li>
 *   <li>POST locale normalisation: Accept-Language en-US → stored 'en'.</li>
 *   <li>POST locale normalisation: Accept-Language th → stored 'th'.</li>
 *   <li>POST locale normalisation: no header → stored 'th' (default).</li>
 *   <li>POST locale normalisation: Accept-Language ja → stored 'th'.</li>
 *   <li>GET list → 200 with items array.</li>
 *   <li>GET list without auth → 401.</li>
 *   <li>GET list returns records in granted_at DESC order.</li>
 *   <li>GET list cursor pagination.</li>
 *   <li>Ungated: POST succeeds even without any consent rows (not protected by ConsentChecker).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class ConsentMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private ConsentRecordRepository consentRecords;
    @Autowired
    private JwtService jwtService;

    private User user;
    private String bearer;

    @BeforeEach
    void seed() {
        consentRecords.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("consent-mvc@example.com");
        user.setEmailVerified(true);
        user = users.saveAndFlush(user);
        bearer = "Bearer " + jwtService.issueAccessToken(user.getId(), true);
    }

    // -------------------------------------------------------------------------
    // POST /account/consents — happy path
    // -------------------------------------------------------------------------

    @Test
    void post_grant_returns201WithCorrectShape() throws Exception {
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "consentType": "general_health",
                                  "granted": true,
                                  "consentTextVersion": "v1.0-th"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.consentType").value("general_health"))
                .andExpect(jsonPath("$.granted").value(true))
                .andExpect(jsonPath("$.consentTextVersion").value("v1.0-th"))
                .andExpect(jsonPath("$.grantedAt").isString());
    }

    @Test
    void post_withdrawal_returns201WithGrantedFalse() throws Exception {
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "consentType": "cloud_storage",
                                  "granted": false,
                                  "consentTextVersion": "v1.0-th"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.granted").value(false))
                .andExpect(jsonPath("$.consentType").value("cloud_storage"));
    }

    @Test
    void post_allSixConsentTypes_return201() throws Exception {
        for (String type : new String[]{"general_health", "sensitive_lab_results",
                "pdf_egress", "infant_feeding", "cloud_storage", "child_health"}) {
            mvc.perform(post("/account/consents")
                            .header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"consentType": "%s", "granted": true, "consentTextVersion": "v1.0"}
                                    """.formatted(type)))
                    .andExpect(status().isCreated());
        }
    }

    // -------------------------------------------------------------------------
    // POST /account/consents — validation errors
    // -------------------------------------------------------------------------

    @Test
    void post_missingConsentType_returns422() throws Exception {
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"granted": true, "consentTextVersion": "v1.0-th"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void post_missingGranted_returns422() throws Exception {
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType": "general_health", "consentTextVersion": "v1.0-th"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void post_missingConsentTextVersion_returns422() throws Exception {
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType": "general_health", "granted": true}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void post_unknownConsentType_returns422() throws Exception {
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "consentType": "not_a_real_type",
                                  "granted": true,
                                  "consentTextVersion": "v1.0-th"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    // -------------------------------------------------------------------------
    // POST /account/consents — auth
    // -------------------------------------------------------------------------

    @Test
    void post_withoutAuth_returns401() throws Exception {
        mvc.perform(post("/account/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType": "general_health", "granted": true,
                                 "consentTextVersion": "v1.0-th"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // POST /account/consents — locale normalisation
    // -------------------------------------------------------------------------

    @Test
    void post_acceptLanguageEnUs_storesLocaleEn() throws Exception {
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        // Verify locale stored in DB is 'en'
        ConsentRecord stored = consentRecords.findAll().stream()
                .filter(r -> r.getUserId().equals(user.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(stored.getLocale()).isEqualTo("en");
    }

    @Test
    void post_acceptLanguageTh_storesLocaleTh() throws Exception {
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .header("Accept-Language", "th-TH,th;q=0.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        ConsentRecord stored = consentRecords.findAll().stream()
                .filter(r -> r.getUserId().equals(user.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(stored.getLocale()).isEqualTo("th");
    }

    @Test
    void post_noAcceptLanguageHeader_defaultsToTh() throws Exception {
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        ConsentRecord stored = consentRecords.findAll().stream()
                .filter(r -> r.getUserId().equals(user.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(stored.getLocale()).isEqualTo("th");
    }

    @Test
    void post_acceptLanguageJa_defaultsToTh() throws Exception {
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .header("Accept-Language", "ja,en;q=0.5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        // Primary tag is 'ja' — not 'en*' — so locale defaults to 'th'
        ConsentRecord stored = consentRecords.findAll().stream()
                .filter(r -> r.getUserId().equals(user.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(stored.getLocale()).isEqualTo("th");
    }

    // -------------------------------------------------------------------------
    // POST /account/consents — ungated: reachable without any prior consent
    // -------------------------------------------------------------------------

    @Test
    void post_isUngated_reachableWithNoPriorConsent() throws Exception {
        // No consent rows exist for this user yet; the endpoint must NOT be blocked
        // by ConsentChecker (it's always reachable per api-contract §353)
        assertThat(consentRecords.findAll()).isEmpty();

        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated()); // NOT 403 consent_required
    }

    // -------------------------------------------------------------------------
    // GET /account/consents — happy path
    // -------------------------------------------------------------------------

    @Test
    void get_emptyHistory_returns200WithEmptyItems() throws Exception {
        mvc.perform(get("/account/consents")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void get_returnsAllRecordsNewestFirst() throws Exception {
        // Post two consents (different types)
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"cloud_storage","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/account/consents")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                // Most recent (cloud_storage) should be first
                .andExpect(jsonPath("$.items[0].consentType").value("cloud_storage"))
                .andExpect(jsonPath("$.items[1].consentType").value("general_health"));
    }

    @Test
    void get_noNextCursorWhenFewItems() throws Exception {
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/account/consents")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist()); // null → excluded by @JsonInclude
    }

    @Test
    void get_cursorPagination_limit1_nextCursorPresent() throws Exception {
        // Insert two consents
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"cloud_storage","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        // First page with limit=1 — should return nextCursor
        String firstPageResponse = mvc.perform(get("/account/consents")
                        .header("Authorization", bearer)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Extract cursor from first page response
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        String cursor = om.readTree(firstPageResponse).get("nextCursor").asText();

        // Second page using cursor — should return the remaining item and no nextCursor
        mvc.perform(get("/account/consents")
                        .header("Authorization", bearer)
                        .param("limit", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // GET /account/consents — auth
    // -------------------------------------------------------------------------

    @Test
    void get_withoutAuth_returns401() throws Exception {
        mvc.perform(get("/account/consents"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // GET /account/consents — IDOR: scoped to own user
    // -------------------------------------------------------------------------

    @Test
    void get_onlyReturnsOwnUserRecords() throws Exception {
        // User B posts a consent
        User userB = new User();
        userB.setEmail("consent-b@example.com");
        userB.setEmailVerified(true);
        userB = users.saveAndFlush(userB);
        String bearerB = "Bearer " + jwtService.issueAccessToken(userB.getId(), true);

        mvc.perform(post("/account/consents")
                        .header("Authorization", bearerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        // User A lists — should see zero items (only their own records)
        mvc.perform(get("/account/consents")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
