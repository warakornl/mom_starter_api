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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for GET /account (api-contract N9 / "Account & sync").
 *
 * <p>Verifies: happy-path response shape, authentication requirement, and that soft-deleted
 * accounts are blocked (PDPA s.33 + consent-hardgate-erasure-design.md §2.6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AccountGetMvcTest {

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
        user.setEmail("mom-get@example.com");
        user.setLocale("th");
        user.setEmailVerified(true);
        user = users.save(user);
        bearer = "Bearer " + jwtService.issueAccessToken(user.getId(), true);
    }

    @Test
    void get_returnsAccountWithExpectedFields() throws Exception {
        mvc.perform(get("/account").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value("mom-get@example.com"))
                .andExpect(jsonPath("$.locale").value("th"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void get_doesNotExposePasswordHash() throws Exception {
        // Contract: NO password hash in the response (PDPA data minimisation)
        mvc.perform(get("/account").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password_hash").doesNotExist());
    }

    @Test
    void get_requiresAuthentication() throws Exception {
        mvc.perform(get("/account"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_returns404ForSoftDeletedUser() throws Exception {
        // Soft-delete the user (PDPA s.33 — consent-hardgate-erasure-design.md §2.6)
        user.setDeletedAt(Instant.now());
        user.setStatus("deleted");
        users.saveAndFlush(user);

        mvc.perform(get("/account").header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    // -------------------------------------------------------------------------
    // FIX C coverage additions
    // -------------------------------------------------------------------------

    @Test
    void get_idor_tokenOnlyReturnsOwnAccount() throws Exception {
        // The userId is extracted from the JWT — user B's token must only ever
        // return user B's own data, never user A's row.
        User userB = new User();
        userB.setEmail("get-b@example.com");
        userB.setLocale("en");
        userB.setEmailVerified(true);
        userB = users.saveAndFlush(userB);
        String bearerB = "Bearer " + jwtService.issueAccessToken(userB.getId(), true);

        mvc.perform(get("/account").header("Authorization", bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("get-b@example.com"));
    }
}
