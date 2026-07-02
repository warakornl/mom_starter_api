package com.momstarter.consent;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.ConsentChecker;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link ConsentRecordConsentChecker} — the real DB-backed checker.
 *
 * <p>Uses {@code @ActiveProfiles("integrationtest")}: the {@code "test"} profile is NOT
 * active, so {@link com.momstarter.pregnancy.AlwaysGrantedConsentChecker} is NOT loaded
 * ({@code @Profile("test")} excludes it).  {@link ConsentRecordConsentChecker} is
 * loaded ({@code @Profile("!test")} is satisfied, {@code @Primary} takes effect).
 *
 * <p>The datasource is overridden via {@link TestPropertySource} to H2 in PostgreSQL mode
 * (same engine as the regular test suite but with a separate in-memory DB instance) so
 * Docker / a real PostgreSQL instance is not required.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>The active {@link ConsentChecker} bean IS {@link ConsentRecordConsentChecker}
 *       (not the stub) — confirms the profile wiring is correct.</li>
 *   <li>No consent row → {@code isGranted()} returns {@code false} (fail-closed).</li>
 *   <li>Granted row → {@code isGranted()} returns {@code true}.</li>
 *   <li>Granted then withdrawn → {@code isGranted()} returns {@code false}.</li>
 *   <li>Gate test: {@code PUT /pregnancy-profile} without a consent row → 403 consent_required.</li>
 *   <li>Gate test: {@code PUT /pregnancy-profile} after granting {@code general_health} → 201/200.</li>
 *   <li>Gate test: withdrawal reverts access → 403 consent_required again.</li>
 * </ul>
 *
 * <p><strong>This test does NOT flip the default production config</strong> — it uses a
 * separate "integrationtest" profile.  The production default remains the stub (Phase 2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integrationtest")
@TestPropertySource(properties = {
        // Override datasource to H2 (integrationtest profile has no application-integrationtest.yml)
        "spring.datasource.url=jdbc:h2:mem:consent_integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        // Disable rate limiting so tests are not throttled
        "momstarter.ratelimit.login-per-ip-per-min=1000000",
        "momstarter.ratelimit.register-per-ip-per-min=1000000",
        "momstarter.ratelimit.resend-per-ip-per-min=1000000",
        "momstarter.ratelimit.forgot-per-ip-per-min=1000000",
        "momstarter.ratelimit.reset-per-ip-per-min=1000000",
        "momstarter.ratelimit.verify-email-per-ip-per-min=1000000",
        // Dev mode must not auto-verify (keep normal auth flow)
        "momstarter.dev.auto-verify-email=false",
        // Activate the real ConsentRecordConsentChecker (the flip) for this integration test
        "momstarter.consent.enforce=true",
})
@Transactional
class ConsentCheckerIntegrationTest {

    @Autowired
    private ConsentChecker consentChecker;

    @Autowired
    private ConsentRecordRepository consentRecords;

    @Autowired
    private UserRepository users;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtService jwtService;

    private User user;
    private String bearer;

    @BeforeEach
    void seed() {
        consentRecords.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("checker-integration@example.com");
        user.setEmailVerified(true);
        user = users.saveAndFlush(user);
        bearer = "Bearer " + jwtService.issueAccessToken(user.getId(), true);
    }

    // -------------------------------------------------------------------------
    // Profile wiring: confirm ConsentRecordConsentChecker is active
    // -------------------------------------------------------------------------

    @Test
    void activeCheckerBean_isConsentRecordConsentChecker() {
        // In the "integrationtest" profile, the real checker must be active — NOT AlwaysGranted
        assertThat(consentChecker)
                .as("ConsentRecordConsentChecker must be the active bean in integrationtest profile")
                .isInstanceOf(ConsentRecordConsentChecker.class);
    }

    // -------------------------------------------------------------------------
    // Fail-closed semantics via direct ConsentChecker call
    // -------------------------------------------------------------------------

    @Test
    void isGranted_noRow_returnsFalse() {
        boolean result = consentChecker.isGranted(user.getId(), "general_health");

        assertThat(result).isFalse();
    }

    @Test
    void isGranted_grantedRow_returnsTrue() throws Exception {
        // POST a grant via the API (goes through service layer, normalises locale etc.)
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        boolean result = consentChecker.isGranted(user.getId(), "general_health");

        assertThat(result).isTrue();
    }

    @Test
    void isGranted_grantThenWithdraw_returnsFalse() throws Exception {
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
                                {"consentType":"general_health","granted":false,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        boolean result = consentChecker.isGranted(user.getId(), "general_health");

        assertThat(result).isFalse();
    }

    @Test
    void isGranted_unknownUser_returnsFalse() {
        UUID unknownUserId = UUID.randomUUID();

        boolean result = consentChecker.isGranted(unknownUserId, "general_health");

        assertThat(result).isFalse();
    }

    // -------------------------------------------------------------------------
    // Gate tests via PUT /pregnancy-profile (general_health gate)
    // -------------------------------------------------------------------------

    // EDD within plausibility window: X-Client-Date 2026-07-02, window is [2026-06-04, 2027-05-06]
    private static final String CLIENT_DATE  = "2026-07-02";
    private static final String VALID_EDD    = "2027-01-01"; // well within the window

    @Test
    void pregnancyProfile_withoutConsent_returns403() throws Exception {
        // Real checker active, no consent row → ConsentChecker.isGranted() = false → 403.
        // Must supply exactly ONE of edd|currentWeek (XOR) so the consent check is reached
        // (the XOR check at step 1 runs before the consent check at step 2 in the service).
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"edd": "%s"}
                                """.formatted(VALID_EDD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("consent_required"));
    }

    @Test
    void pregnancyProfile_afterGrantingGeneralHealth_succeeds() throws Exception {
        // Grant general_health consent first
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        // Now PUT /pregnancy-profile must succeed (gate open)
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"edd": "%s"}
                                """.formatted(VALID_EDD)))
                .andExpect(status().is2xxSuccessful()); // 201 create or 200 update
    }

    @Test
    void pregnancyProfile_afterWithdrawingConsent_returns403Again() throws Exception {
        // Grant, then use pregnancy-profile, then withdraw
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":true,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        // Withdraw consent
        mvc.perform(post("/account/consents")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"general_health","granted":false,"consentTextVersion":"v1.0"}
                                """))
                .andExpect(status().isCreated());

        // PUT /pregnancy-profile must now be blocked again
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"edd": "%s"}
                                """.formatted(VALID_EDD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("consent_required"));
    }
}
