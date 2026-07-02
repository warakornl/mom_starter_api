package com.momstarter.account;

import com.momstarter.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for PATCH /account (api-contract B2 / "PATCH /account direct-REST").
 *
 * <p>Covers: locale update, email update, If-Match enforcement (428 / 409), invalid locale (422),
 * email-conflict non-enumeration (422), and authentication requirement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AccountPatchMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private JwtService jwtService;

    private User user;
    private String bearer;

    @BeforeEach
    void seed() {
        users.deleteAll();
        user = new User();
        user.setEmail("mom-patch@example.com");
        user.setLocale("th");
        user.setEmailVerified(true);
        user = users.saveAndFlush(user);
        bearer = "Bearer " + jwtService.issueAccessToken(user.getId(), true);
    }

    @Test
    void patch_updatesLocale_returns200() throws Exception {
        mvc.perform(patch("/account")
                        .header("Authorization", bearer)
                        .header("If-Match", "\"" + user.getVersion() + "\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"locale\":\"en\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.email").value("mom-patch@example.com"));
    }

    @Test
    void patch_updatesEmail_returns200() throws Exception {
        mvc.perform(patch("/account")
                        .header("Authorization", bearer)
                        .header("If-Match", "\"" + user.getVersion() + "\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"mom-new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("mom-new@example.com"));
    }

    @Test
    void patch_emailChange_setsEmailVerifiedFalse() throws Exception {
        mvc.perform(patch("/account")
                        .header("Authorization", bearer)
                        .header("If-Match", "\"" + user.getVersion() + "\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"mom-new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void patch_withoutIfMatch_returns428() throws Exception {
        // If-Match required for direct-REST mutations (api-contract B2)
        mvc.perform(patch("/account")
                        .header("Authorization", bearer)
                        .contentType(APPLICATION_JSON)
                        .content("{\"locale\":\"en\"}"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("precondition_required"));
    }

    @Test
    void patch_withStaleVersion_returns409WithCurrentAccountBody() throws Exception {
        // Stale If-Match → 409 with the current authoritative account in the body (B2)
        mvc.perform(patch("/account")
                        .header("Authorization", bearer)
                        .header("If-Match", "\"9999\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"locale\":\"en\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.email").value("mom-patch@example.com"))
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    void patch_withInvalidLocale_returns422() throws Exception {
        // Locale must be th or en (api-contract AccountInput enum)
        mvc.perform(patch("/account")
                        .header("Authorization", bearer)
                        .header("If-Match", "\"" + user.getVersion() + "\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"locale\":\"fr\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void patch_withEmailAlreadyTakenByAnotherUser_returns422NonEnumerating() throws Exception {
        // Non-enumerating contract-gap resolution: another user's email → 422 validation_error
        // (does NOT reveal that the address already exists in the system)
        User other = new User();
        other.setEmail("taken@example.com");
        other.setEmailVerified(true);
        users.saveAndFlush(other);

        mvc.perform(patch("/account")
                        .header("Authorization", bearer)
                        .header("If-Match", "\"" + user.getVersion() + "\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"taken@example.com\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void patch_emptyBody_returns200Unchanged() throws Exception {
        // Both fields optional: {} is a valid no-op → returns unchanged account
        mvc.perform(patch("/account")
                        .header("Authorization", bearer)
                        .header("If-Match", "\"" + user.getVersion() + "\"")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("mom-patch@example.com"))
                .andExpect(jsonPath("$.locale").value("th"));
    }

    @Test
    void patch_requiresAuthentication() throws Exception {
        mvc.perform(patch("/account")
                        .contentType(APPLICATION_JSON)
                        .content("{\"locale\":\"en\"}"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // FIX C coverage additions
    // -------------------------------------------------------------------------

    @Test
    void patch_withMalformedIfMatch_returns412() throws Exception {
        // parseIfMatch throws 412 precondition_failed when the value cannot be
        // parsed as a number (e.g. "abc" is not a valid version token).
        mvc.perform(patch("/account")
                        .header("Authorization", bearer)
                        .header("If-Match", "\"abc\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"locale\":\"en\"}"))
                .andExpect(status().is(412))
                .andExpect(jsonPath("$.code").value("precondition_failed"));
    }

    @Test
    void patch_afterSoftDelete_returns404() throws Exception {
        // requireActiveUser blocks soft-deleted accounts before the If-Match check
        user.setDeletedAt(Instant.now());
        user.setStatus("deleted");
        users.saveAndFlush(user);

        mvc.perform(patch("/account")
                        .header("Authorization", bearer)
                        .header("If-Match", "\"" + user.getVersion() + "\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"locale\":\"en\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void patch_idor_tokenOnlyAffectsOwnAccount() throws Exception {
        // The userId is extracted from the JWT, never from a URL parameter.
        // User B's token can only ever mutate user B's row — never user A's.
        User userB = new User();
        userB.setEmail("patch-b@example.com");
        userB.setLocale("en");
        userB.setEmailVerified(true);
        userB = users.saveAndFlush(userB);
        String bearerB = "Bearer " + jwtService.issueAccessToken(userB.getId(), true);

        // User B patches with their own token → response belongs to user B
        mvc.perform(patch("/account")
                        .header("Authorization", bearerB)
                        .header("If-Match", "\"" + userB.getVersion() + "\"")
                        .contentType(APPLICATION_JSON)
                        .content("{\"locale\":\"th\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("patch-b@example.com"));

        // User A's account is entirely untouched
        User foundA = users.findById(user.getId()).orElseThrow();
        assertThat(foundA.getEmail()).isEqualTo("mom-patch@example.com");
        assertThat(foundA.getLocale()).isEqualTo("th");
    }
}
