package com.momstarter.account;

import com.momstarter.auth.JwtService;
import com.momstarter.auth.RefreshTokenRepository;
import com.momstarter.auth.RefreshTokenService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for DELETE /account (api-contract §567, PDPA s.33).
 *
 * <p>Verifies: 202 response, soft-delete (deleted_at set), session revocation,
 * blocked login after deletion, and authentication requirement.
 *
 * <p>Hard-erasure (cascade DELETE of child rows + users row) is NOT tested here —
 * it is a separate prod-gate (consent-hardgate-erasure-design.md §2.6 blocker D).
 * This slice closes the "no writer for users.deleted_at" gap flagged by compliance-reviewer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AccountDeleteMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private RefreshTokenService refreshTokens;
    @Autowired
    private RefreshTokenRepository tokens;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private AccountDekRepository accountDekRepository;
    @PersistenceContext
    private EntityManager em;

    private User user;
    private String bearer;

    @BeforeEach
    void seed() {
        tokens.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("mom-delete@example.com");
        user.setLocale("th");
        user.setEmailVerified(true);
        user.setPasswordHash(encoder.encode("password123!"));
        user = users.saveAndFlush(user);
        bearer = "Bearer " + jwtService.issueAccessToken(user.getId(), true);
    }

    @Test
    void delete_returns202Accepted() throws Exception {
        mvc.perform(delete("/account").header("Authorization", bearer))
                .andExpect(status().isAccepted());
    }

    @Test
    void delete_softDeletesUser_setsDeletedAtAndStatus() throws Exception {
        mvc.perform(delete("/account").header("Authorization", bearer))
                .andExpect(status().isAccepted());

        User found = users.findById(user.getId()).orElseThrow();
        assertThat(found.getDeletedAt()).isNotNull();
        assertThat(found.getStatus()).isEqualTo("deleted");
    }

    @Test
    void delete_revokesAllRefreshTokenFamilies() throws Exception {
        // Mint an active refresh token before deletion
        RefreshTokenService.Issued issued =
                refreshTokens.mintFamily(user.getId(), "device-1", "Test Device");

        mvc.perform(delete("/account").header("Authorization", bearer))
                .andExpect(status().isAccepted());

        // The issued token must be revoked
        assertThat(tokens.findByTokenHash(RefreshTokenService.sha256Hex(issued.rawToken())))
                .isPresent()
                .hasValueSatisfying(rt -> assertThat(rt.getRevokedAt()).isNotNull());
    }

    @Test
    void delete_blocksSubsequentLogin() throws Exception {
        mvc.perform(delete("/account").header("Authorization", bearer))
                .andExpect(status().isAccepted());

        // Login with the same credentials must fail (non-enumerating: same 401 as wrong pw)
        mvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"mom-delete@example.com\",\"password\":\"password123!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @Test
    void delete_blocksSubsequentGetAccount() throws Exception {
        // Access token remains technically valid for up to 15 min after deletion,
        // but GET /account must return 404 (deleted-user guard in AccountService).
        mvc.perform(delete("/account").header("Authorization", bearer))
                .andExpect(status().isAccepted());

        mvc.perform(get("/account").header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void delete_isIdempotent() throws Exception {
        // Deleting twice is a no-op on the second call (same 202, no error)
        mvc.perform(delete("/account").header("Authorization", bearer))
                .andExpect(status().isAccepted());
        mvc.perform(delete("/account").header("Authorization", bearer))
                .andExpect(status().isAccepted());
    }

    @Test
    void delete_requiresAuthentication() throws Exception {
        mvc.perform(delete("/account"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Crypto-shred wiring (sub-slice c) — tests (a) and (b)
    // -------------------------------------------------------------------------

    /**
     * (a) T0 crypto-shred: after {@code DELETE /account}, the {@code account_dek} row for the
     * authenticated user MUST be hard-deleted in the same transaction as
     * {@code setStatus("deleted")} (ADR CRITICAL-1 / migration-design T0 sequence).
     *
     * <p>Proves: the wrapped-DEK row is gone immediately after account deletion — KMS.Decrypt
     * would subsequently be impossible, making all *_cipher bytes irrecoverable at T0.
     *
     * <p>TDD: test written before {@link AccountService} calls
     * {@link AccountDekRepository#deleteByUserId}. It fails until that call is wired.
     */
    @Test
    void delete_t0CryptoShred_deletesAccountDekRow() throws Exception {
        AccountDek dek = buildTestDek(user.getId());
        accountDekRepository.saveAndFlush(dek);

        mvc.perform(delete("/account").header("Authorization", bearer))
                .andExpect(status().isAccepted());

        // Clear L1 cache so findById issues a fresh SELECT (not cached entity).
        // Matches the pattern in SelfLogPgSmokeTest / AccountErasureServiceTest.
        em.flush();
        em.clear();

        assertThat(accountDekRepository.findById(user.getId()))
                .as("T0 crypto-shred: account_dek row must be hard-deleted (ADR CRITICAL-1)")
                .isEmpty();
    }

    /**
     * (b) Idempotent DEK delete: an account with no provisioned DEK row can be deleted without
     * error — {@link AccountDekRepository#deleteByUserId} is a no-op when the row is absent
     * (0 rows deleted, no exception). This covers late-lifecycle accounts that were never
     * fully provisioned, and ensures the crypto-shred wiring doesn't break the delete path.
     */
    @Test
    void delete_noDekRow_cryptoShredIsNoOp_succeeds() throws Exception {
        // Confirm no DEK row exists for this user
        assertThat(accountDekRepository.findById(user.getId())).isEmpty();

        mvc.perform(delete("/account").header("Authorization", bearer))
                .andExpect(status().isAccepted());

        em.flush();
        em.clear();

        // User is soft-deleted; DEK row was never there and still isn't
        User found = users.findById(user.getId()).orElseThrow();
        assertThat(found.getDeletedAt()).isNotNull();
        assertThat(found.getStatus()).isEqualTo("deleted");
        assertThat(accountDekRepository.findById(user.getId())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // FIX C coverage additions
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AccountDek buildTestDek(java.util.UUID userId) {
        AccountDek dek = new AccountDek();
        dek.setUserId(userId);
        dek.setWrappedDek(new byte[]{1, 2, 3, 4});
        dek.setKmsKeyId("mock-cmk/test");
        dek.setWrapContext("accountId=" + userId);
        return dek;
    }

    @Test
    void delete_idor_tokenOnlyDeletesOwnAccount() throws Exception {
        // The userId is extracted from the JWT — user B's token soft-deletes
        // user B only; user A's row must be completely unaffected.
        User userB = new User();
        userB.setEmail("delete-b@example.com");
        userB.setLocale("th");
        userB.setEmailVerified(true);
        userB = users.saveAndFlush(userB);
        String bearerB = "Bearer " + jwtService.issueAccessToken(userB.getId(), true);

        mvc.perform(delete("/account").header("Authorization", bearerB))
                .andExpect(status().isAccepted());

        // User B is soft-deleted
        User deletedB = users.findById(userB.getId()).orElseThrow();
        assertThat(deletedB.getDeletedAt()).isNotNull();
        assertThat(deletedB.getStatus()).isEqualTo("deleted");

        // User A is entirely untouched
        User activeA = users.findById(user.getId()).orElseThrow();
        assertThat(activeA.getDeletedAt()).isNull();
        assertThat(activeA.getStatus()).isEqualTo("active");
    }
}
