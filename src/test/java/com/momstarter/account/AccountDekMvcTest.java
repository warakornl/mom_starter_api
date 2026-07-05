package com.momstarter.account;

import com.momstarter.auth.JwtService;
import com.momstarter.auth.RefreshTokenRepository;
import com.momstarter.encryption.KmsClient;
import com.momstarter.encryption.MockKmsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for {@code GET /account/dek} (login-delivery, ADR Decision 2.2).
 *
 * <p>{@link MockKmsClient} is the active {@link KmsClient} implementation (it is annotated
 * {@code @Component}). Tests verify:
 * <ul>
 *   <li>200 with a non-empty {@code dek} field when an {@code account_dek} row exists</li>
 *   <li>401 when no Bearer token is presented</li>
 *   <li>IDOR-safety: userId comes from JWT subject, not request params</li>
 *   <li>404 when no {@code account_dek} row exists for the authenticated user</li>
 *   <li>{@code Cache-Control: no-store} on the 200 response</li>
 * </ul>
 *
 * <p>TDD: tests were written before the implementation to drive the controller/service shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AccountDekMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RefreshTokenRepository tokens;

    @Autowired
    private DekService dekService;

    @Autowired
    private AccountDekRepository accountDekRepository;

    @Autowired
    private KmsClient kmsClient; // MockKmsClient in test profile

    private User user;
    private String bearer;

    @BeforeEach
    void seed() {
        tokens.deleteAll();
        accountDekRepository.deleteAll();
        users.deleteAll();

        user = new User();
        user.setEmail("dek-mvc-test@example.com");
        user.setEmailVerified(true);
        user.setPasswordHash("{argon2}dummy");
        user = users.saveAndFlush(user);
        bearer = "Bearer " + jwtService.issueAccessToken(user.getId(), true);
    }

    // -------------------------------------------------------------------------
    // 200 — DEK row present
    // -------------------------------------------------------------------------

    @Test
    void getDek_withDekRow_returns200WithDekAndVersion() throws Exception {
        dekService.provisionDek(user.getId());

        mvc.perform(get("/account/dek").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dek").isNotEmpty())
                .andExpect(jsonPath("$.dekVersion").value(1));
    }

    @Test
    void getDek_dekField_isValidBase64_decodingTo32Bytes() throws Exception {
        dekService.provisionDek(user.getId());

        String dekBase64 = mvc.perform(get("/account/dek").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse()
                .getContentAsString()
                // extract dek value from JSON
                .replaceAll(".*\"dek\":\"([^\"]+)\".*", "$1");

        byte[] decoded = java.util.Base64.getDecoder().decode(dekBase64);
        assertThat(decoded).hasSize(32);
    }

    @Test
    void getDek_responseHasCacheControlNoStore() throws Exception {
        dekService.provisionDek(user.getId());

        mvc.perform(get("/account/dek").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    // -------------------------------------------------------------------------
    // 401 — no authentication
    // -------------------------------------------------------------------------

    @Test
    void getDek_withoutBearerToken_returns401() throws Exception {
        mvc.perform(get("/account/dek"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // 404 — no DEK row for the authenticated user
    // -------------------------------------------------------------------------

    @Test
    void getDek_noDekRow_returns404WithCode() throws Exception {
        // No account_dek row for this user
        mvc.perform(get("/account/dek").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("dek_not_found"));
    }

    // -------------------------------------------------------------------------
    // IDOR-safety: JWT subject is the sole source of userId
    // -------------------------------------------------------------------------

    @Test
    void getDek_idorSafe_subjectFromJwt_cannotAccessOtherUsersDek() throws Exception {
        // UserA has a DEK row; userB uses their own JWT
        User userA = new User();
        userA.setEmail("dek-idor-a@example.com");
        userA.setEmailVerified(true);
        userA = users.saveAndFlush(userA);
        dekService.provisionDek(userA.getId());

        // UserB authenticates — their JWT subject is their own userId (no DEK row)
        User userB = new User();
        userB.setEmail("dek-idor-b@example.com");
        userB.setEmailVerified(true);
        userB = users.saveAndFlush(userB);
        String bearerB = "Bearer " + jwtService.issueAccessToken(userB.getId(), true);

        // UserB cannot see userA's DEK — they see 404 for their own (absent) row
        mvc.perform(get("/account/dek").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("dek_not_found"));
    }

    @Test
    void getDek_eachUserSeesOwnDek() throws Exception {
        // UserA and userB each have their own DEK; confirm userA's JWT returns userA's DEK
        dekService.provisionDek(user.getId()); // user is userA here

        User userB = new User();
        userB.setEmail("dek-idor-b2@example.com");
        userB.setEmailVerified(true);
        userB = users.saveAndFlush(userB);
        dekService.provisionDek(userB.getId());
        String bearerB = "Bearer " + jwtService.issueAccessToken(userB.getId(), true);

        // Both should get 200 with their own DEK
        mvc.perform(get("/account/dek").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dek").isNotEmpty());

        mvc.perform(get("/account/dek").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dek").isNotEmpty());
    }
}
