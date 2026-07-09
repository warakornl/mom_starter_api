package com.momstarter.auth;

import com.momstarter.config.TestSyncAsyncConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-CORE-9 / contract §H — per-IP rate limit for POST /auth/forgot-password.
 *
 * <p>The per-account bucket is set very high (1_000_000) so it never fires during
 * this test, isolating the per-IP code path in
 * {@link PasswordRecoveryService#forgotPassword(String, String)}.
 *
 * <p>The per-IP bucket is set to 2 so it can be exhausted in three requests.
 * The key passed to {@link RateLimiter} is {@code "forgot-ip:" + clientIp}, where
 * {@code clientIp} comes from {@code HttpServletRequest.getRemoteAddr()}.
 * MockMvc lets us vary the remote address per request via
 * {@code RequestBuilder.with(r -> { r.setRemoteAddr(ip); return r; })}.
 *
 * <p>Non-vacuousness guarantee: if {@code momstarter.ratelimit.forgot-per-ip-per-min}
 * were removed or set to a very large value the third request from IP_A would return
 * 202 instead of 429, making this test FAIL.  The test therefore genuinely exercises
 * the per-IP enforcement path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        // Per-account bucket is huge — it must never fire so the per-IP path is isolated
        "momstarter.ratelimit.forgot-per-account-per-min=1000000",
        // Per-IP bucket set to 2 so we can exhaust it with 3 requests in the test
        "momstarter.ratelimit.forgot-per-ip-per-min=2"
})
@Import(TestSyncAsyncConfig.class)
@Transactional
class ForgotPasswordIpRateLimitTest {

    private static final String IP_A = "10.10.10.1";
    private static final String IP_B = "10.10.10.2";

    @Autowired
    private MockMvc mvc;

    /** Stub so the async worker does not try to send real email during tests. */
    @MockBean
    private PasswordEmailSender sender;

    /**
     * T-IP-1: exhaust the per-IP bucket and prove isolation across IPs.
     *
     * <ul>
     *   <li>Requests 1 and 2 from IP_A → 202 (within the allowed 2-per-min window)</li>
     *   <li>Request 3 from IP_A → 429 rate_limited (bucket exhausted)</li>
     *   <li>Request 1 from IP_B → 202 (separate bucket, unaffected by IP_A exhaustion)</li>
     * </ul>
     *
     * <p>Different emails are used for each request so the per-ACCOUNT bucket (capped at
     * 1_000_000) is never approached.  This confirms the 429 on the third request is
     * caused exclusively by the per-IP bucket, not the per-account bucket.
     */
    @Test
    void perIpBucket_exhausted_returns429_andDifferentIpIsUnaffected() throws Exception {
        // First two requests from IP_A succeed — within the 2-request window
        sendForgot("ghost1@example.com", IP_A).andExpect(status().isAccepted());
        sendForgot("ghost2@example.com", IP_A).andExpect(status().isAccepted());

        // Third request from IP_A exhausts the per-IP bucket → 429 rate_limited
        sendForgot("ghost3@example.com", IP_A)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"));

        // A request from a DIFFERENT IP has its own fresh bucket → still 202
        // (proves the bucket is keyed on IP, not applied globally)
        sendForgot("ghost4@example.com", IP_B).andExpect(status().isAccepted());
    }

    // ---- helpers ----

    private ResultActions sendForgot(String email, String clientIp) throws Exception {
        return mvc.perform(
                post("/auth/forgot-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}")
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
        );
    }
}
