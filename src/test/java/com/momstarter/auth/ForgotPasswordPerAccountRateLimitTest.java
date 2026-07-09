package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.config.TestSyncAsyncConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-CORE-9: per-account (email) rate limit must fire independently of the per-IP bucket.
 * Both buckets must coexist; exceeding either returns 429 rate_limited.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        // Deliberately high IP limit so per-IP never fires — lets us prove the per-account bucket
        "momstarter.ratelimit.forgot-per-ip-per-min=1000000",
        // Per-account limit set to 2 so we can exhaust it in the test quickly
        "momstarter.ratelimit.forgot-per-account-per-min=2"
})
@Import(TestSyncAsyncConfig.class)
@Transactional
class ForgotPasswordPerAccountRateLimitTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @MockBean
    private PasswordEmailSender sender;

    private String forgotEmail(String email) throws Exception {
        return mvc.perform(post("/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void perAccountBucket_exhausted_returns429() throws Exception {
        User u = new User();
        u.setEmail("target@example.com");
        u.setPasswordHash(encoder.encode("password123"));
        users.save(u);

        // First two requests should succeed (status 202)
        mvc.perform(post("/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"target@example.com\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"target@example.com\"}"))
                .andExpect(status().isAccepted());

        // Third request exceeds per-account bucket → 429
        mvc.perform(post("/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"target@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"));
    }

    @Test
    void perAccountBucket_onlyThrottlesTargetEmail_notOtherEmails() throws Exception {
        // Seed two accounts
        User u1 = new User();
        u1.setEmail("victim@example.com");
        u1.setPasswordHash(encoder.encode("password123"));
        users.save(u1);

        User u2 = new User();
        u2.setEmail("other@example.com");
        u2.setPasswordHash(encoder.encode("password123"));
        users.save(u2);

        // Exhaust bucket for victim
        forgotEmail("victim@example.com");
        forgotEmail("victim@example.com");

        // victim → 429
        mvc.perform(post("/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"victim@example.com\"}"))
                .andExpect(status().isTooManyRequests());

        // other account → still 202 (its bucket is separate)
        mvc.perform(post("/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"other@example.com\"}"))
                .andExpect(status().isAccepted());
    }

}
