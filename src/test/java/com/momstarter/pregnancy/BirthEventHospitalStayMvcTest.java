package com.momstarter.pregnancy;

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
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for the hospital-stay field behaviour on
 * {@code POST /pregnancy-profile/birth-event} (contract L227 — pregnancy-summary feature).
 *
 * <h2>No-op suppression rule (contract L227 — load-bearing pin)</h2>
 * <p>The birth-event no-op short-circuit (already-postpartum AND birthDate unchanged → 200,
 * version NOT bumped) MUST be suppressed when ANY hospital-stay key is present in the request
 * (value OR explicit null). Presence-of-key is the trigger (NOT value-equality), because
 * ciphers are random-IV bytea and can never be byte-diffed.
 *
 * <p>Example scenario: mother records birth day 0, then re-POSTs same birthDate +
 * hospitalDischargeDate days later. Without suppression the second POST would be swallowed
 * as a no-op and the discharge date would be lost.
 *
 * <h2>Covered cases</h2>
 * <ul>
 *   <li><b>Case 1</b>: already-postpartum + same birthDate + hospital key (value) present
 *       → persists + version bumped (NOT a no-op)</li>
 *   <li><b>Case 2</b>: already-postpartum + same birthDate + NO hospital key
 *       → true no-op → version NOT bumped (guard: existing behaviour preserved)</li>
 *   <li><b>Case 3</b>: already-postpartum + same birthDate + explicit-null hospital key
 *       → clears cipher + version bumped (null key IS a real mutation)</li>
 *   <li><b>Case 4</b>: hospital-date ciphertext larger than byte-cap
 *       → 422 validation_error with details {@code hospital_date_too_large}</li>
 * </ul>
 *
 * <p>Test EDD: 2026-10-01 | client-date: 2026-06-29 | birthDate: 2026-06-29
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class BirthEventHospitalStayMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private PregnancyProfileRepository profiles;
    @Autowired private JwtService jwtService;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;

    private static final String CLIENT_DATE = "2026-06-29";
    private static final String EDD_STR     = "2026-10-01";
    private static final String BIRTH_DATE  = "2026-06-29";

    /**
     * Small valid ciphertext placeholder (arbitrary 5 bytes, Base64-encoded).
     * Represents a real hospital-date cipher sent from the client.
     */
    private static final String SAMPLE_CIPHER =
            Base64.getEncoder().encodeToString(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05});

    /**
     * Ciphertext that decodes to 8193 bytes — one byte over the 8192-byte cap.
     * Expected to trigger 422 validation_error(hospital_date_too_large).
     */
    private static final String TOO_LARGE_CIPHER =
            Base64.getEncoder().encodeToString(new byte[8193]);

    @BeforeEach
    void seed() {
        profiles.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("hospital-stay@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        bearer = jwtService.issueAccessToken(user.getId(), true);
        when(consentChecker.isGranted(any(), any())).thenReturn(true);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Creates a pregnant profile with EDD_STR via PUT (version starts at 0). */
    private void createPregnantProfile() throws Exception {
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"" + EDD_STR + "\"}"))
                .andExpect(status().isCreated());
    }

    /**
     * Transitions the profile to postpartum with BIRTH_DATE (version 0 → 1).
     * Asserts the transition succeeds (200, version=1).
     */
    private void transitionToPostpartum() throws Exception {
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthDate\":\"" + BIRTH_DATE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    // =========================================================================
    // Case 1: hospital key (value) present + same birthDate → version bumps
    // =========================================================================

    /**
     * Contract L227 — no-op suppression case A:
     * Already postpartum + same birthDate + hospitalAdmissionDate key present (with a value)
     * → the no-op short-circuit MUST be suppressed → update persisted, version bumped 1 → 2.
     *
     * <p>Real scenario: mother records birth day 0 (version 0→1), then re-POSTs same birthDate
     * + hospital admission date cipher days later → must persist (version 1→2), NOT be swallowed.
     * RED: fails until PregnancyProfileService suppresses no-op on hospital key presence.
     */
    @Test
    void alreadyPostpartum_sameBirthDate_withHospitalAdmissionKey_persistsAndBumpsVersion()
            throws Exception {
        createPregnantProfile();
        transitionToPostpartum();  // version 0 → 1

        // Re-POST: same birthDate + hospitalAdmissionDate cipher → NOT a no-op
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthDate\":\"" + BIRTH_DATE + "\","
                                + "\"hospitalAdmissionDate\":\"" + SAMPLE_CIPHER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("postpartum"))
                .andExpect(jsonPath("$.version").value(2));  // MUST bump: hospital key present

        // Verify cipher stored in DB
        PregnancyProfile p = profiles.findByUserIdAndDeletedAtIsNull(user.getId()).orElseThrow();
        assertThat(p.getHospitalAdmissionDateCipher())
                .as("hospitalAdmissionDateCipher must be persisted after POST with value")
                .isEqualTo(Base64.getDecoder().decode(SAMPLE_CIPHER));
    }

    // =========================================================================
    // Case 2: no hospital key + same birthDate → true no-op (guard)
    // =========================================================================

    /**
     * Contract L227 — no-op guard: the existing OQ-12/PP6 no-op still fires when there
     * is NO hospital key and the birthDate is unchanged.
     *
     * <p>This guard ensures Case 1's suppression does not accidentally break the normal
     * idempotency path. A birthDate-only echo re-POST MUST remain a no-op.
     * GREEN even before the hospital-stay implementation (existing behaviour preserved).
     */
    @Test
    void alreadyPostpartum_sameBirthDate_noHospitalKey_isNoOp_versionNotBumped()
            throws Exception {
        createPregnantProfile();
        transitionToPostpartum();  // version 0 → 1

        // Re-POST: ONLY birthDate, no hospital keys → true no-op → version stays 1
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthDate\":\"" + BIRTH_DATE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("postpartum"))
                .andExpect(jsonPath("$.version").value(1));  // NOT bumped: true no-op
    }

    // =========================================================================
    // Case 3: explicit-null hospital key → clears cipher + version bumps
    // =========================================================================

    /**
     * Contract L227 — no-op suppression case B (explicit null):
     * Already postpartum + same birthDate + explicit-null hospital key
     * → the null key IS a real mutation (clear operation) → no-op suppressed → version bumps.
     *
     * <p>The presence-of-key rule means even {@code "hospitalAdmissionDate": null} in JSON
     * triggers a write (clearing the cipher column to NULL). This prevents the case where a
     * mother tries to retract a previously recorded date but the request is silently swallowed.
     *
     * <p>Setup: seed the hospitalAdmissionDateCipher directly via repository to decouple this
     * test from Case 1 (avoids test-order dependency in the RED phase).
     * RED: fails until PregnancyProfileService recognises null Optional as presence-of-key.
     */
    @Test
    void alreadyPostpartum_sameBirthDate_explicitNullHospitalKey_clearsAndBumpsVersion()
            throws Exception {
        createPregnantProfile();
        transitionToPostpartum();  // version 0 → 1

        // Directly seed a hospital admission cipher on the entity (bypasses birth-event handler)
        // so we can verify it is cleared by the explicit-null POST below.
        PregnancyProfile p = profiles.findByUserIdAndDeletedAtIsNull(user.getId()).orElseThrow();
        p.setHospitalAdmissionDateCipher(new byte[]{0x41, 0x42, 0x43});  // "ABC" placeholder
        profiles.saveAndFlush(p);
        // JPA @Version bumped to 2 by the saveAndFlush above

        // POST with same birthDate + explicit-null hospitalAdmissionDate → clears + bumps version
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthDate\":\"" + BIRTH_DATE + "\","
                                + "\"hospitalAdmissionDate\":null}"))  // explicit null = clear
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));  // MUST bump: key present (even null)

        // Verify cipher is cleared (null) in DB
        PregnancyProfile after = profiles.findByUserIdAndDeletedAtIsNull(user.getId()).orElseThrow();
        assertThat(after.getHospitalAdmissionDateCipher())
                .as("hospitalAdmissionDateCipher must be NULL after explicit-null POST")
                .isNull();
    }

    // =========================================================================
    // Case 4: ciphertext too large → 422 validation_error(hospital_date_too_large)
    // =========================================================================

    /**
     * Hospital-date ciphertext that Base64-decodes to more than 8192 bytes must be rejected
     * with {@code 422 validation_error} and details {@code hospital_date_too_large}.
     *
     * <p>The server never parses the date (client-side temporal validation) but does enforce
     * a generous byte-cap to guard against oversized payloads.
     * The sub-code mirrors {@code name_too_large} (api-contract byte-cap pattern).
     * RED: fails until PregnancyProfileService.validateHospitalDateCipherSize is added.
     */
    @Test
    void hospitalAdmissionDate_tooLarge_returns422_hospitalDateTooLarge() throws Exception {
        createPregnantProfile();  // version 0 (pregnant)

        // POST birth-event: valid birthDate + hospital cipher that exceeds 8192 bytes
        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthDate\":\"" + BIRTH_DATE + "\","
                                + "\"hospitalAdmissionDate\":\"" + TOO_LARGE_CIPHER + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.details").value("hospital_date_too_large"));
    }

    /**
     * Discharge-date cipher too large also triggers 422 hospital_date_too_large.
     * Both hospital date fields share the same byte-cap guard.
     */
    @Test
    void hospitalDischargeDate_tooLarge_returns422_hospitalDateTooLarge() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/birth-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthDate\":\"" + BIRTH_DATE + "\","
                                + "\"hospitalDischargeDate\":\"" + TOO_LARGE_CIPHER + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.details").value("hospital_date_too_large"));
    }
}
