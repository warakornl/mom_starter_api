package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T-14 — Timing-parity test (BE-CORE-1). Measures p50/p95 latency of the forgot-password
 * HTTP response for both "email exists" and "email does not exist" branches.
 *
 * <p>The async approach makes the HTTP path constant-time: both branches do the same work
 * (rate-limit check + task submission → 202). The actual DB/send work happens asynchronously
 * on a thread-pool thread <em>after</em> the HTTP response is sent. Therefore the HTTP response
 * latency does not reveal whether the account exists.
 *
 * <p>This test does NOT use {@code @Transactional} so that the seeded user is committed and
 * visible to the async worker thread. It also does NOT use {@code TestSyncAsyncConfig} so that
 * the truly asynchronous path is exercised (measuring synchronous dispatch would trivially pass
 * but would not prove the async timing invariant).
 *
 * <p>Tolerance factor 5× is generous enough to absorb JVM warm-up, H2 scheduling jitter,
 * and occasional GC pauses while still catching a regression where the HTTP path blocks on
 * the account-existence check.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "momstarter.ratelimit.forgot-per-ip-per-min=1000000",
        "momstarter.ratelimit.forgot-per-account-per-min=1000000"
})
@Tag("timing")
class ForgotPasswordTimingParityTest {

    private static final int WARMUP = 10;
    private static final int SAMPLES = 60;
    // Generous tolerance: HTTP path should be near-identical, but allow for JVM variance
    private static final double MAX_RATIO = 5.0;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordResetTokenRepository resetTokens;
    @Autowired
    private PasswordEncoder encoder;
    @MockBean
    private PasswordEmailSender sender;

    private User seededUser;

    @BeforeEach
    void seed() {
        // Save WITHOUT @Transactional so the user is committed → visible to async worker thread
        User u = new User();
        u.setEmail("timing-exists@example.com");
        u.setPasswordHash(encoder.encode("correcthorsebattery"));
        seededUser = users.save(u);
    }

    @AfterEach
    void cleanup() {
        if (seededUser != null) {
            // Delete FK-dependent rows first: password_reset_token.user_id → users.id
            resetTokens.findAll().stream()
                    .filter(t -> seededUser.getId().equals(t.getUserId()))
                    .forEach(t -> resetTokens.deleteById(t.getId()));
            users.deleteById(seededUser.getId());
        }
    }

    @Test
    void existsAndNotExistsBranches_httpLatency_isStatisticallyIndistinguishable() throws Exception {
        // Warm up both paths to let JIT compile the hot paths
        for (int i = 0; i < WARMUP; i++) {
            doForgot("timing-exists@example.com");
            doForgot("ghost-warmup-" + i + "@example.com");
        }

        // Wait for warmup's async workers to finish (they're running on the thread pool)
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(sender, atLeastOnce()).sendPasswordReset(anyString(), anyString()));

        List<Long> existsNanos = new ArrayList<>(SAMPLES);
        List<Long> notExistsNanos = new ArrayList<>(SAMPLES);

        for (int i = 0; i < SAMPLES; i++) {
            existsNanos.add(measureNanos("timing-exists@example.com"));
            notExistsNanos.add(measureNanos("ghost-sample-" + i + "@example.com"));
        }

        long p50existsNs = percentile(existsNanos, 50);
        long p95existsNs = percentile(existsNanos, 95);
        long p50notExistsNs = percentile(notExistsNanos, 50);
        long p95notExistsNs = percentile(notExistsNanos, 95);

        long safeP95NotExists = Math.max(p95notExistsNs, 1L);
        long safeP50NotExists = Math.max(p50notExistsNs, 1L);

        double p50ratio = (double) p50existsNs / safeP50NotExists;
        double p95ratio = (double) p95existsNs / safeP95NotExists;

        assertThat(p95ratio)
                .as("p95 latency ratio (exists/not-exists) must be ≤ %.1f× — " +
                        "got exists p95=%dns, not-exists p95=%dns. " +
                        "A ratio >> 1 indicates the HTTP path blocks on account existence (timing oracle).",
                        MAX_RATIO, p95existsNs, p95notExistsNs)
                .isLessThanOrEqualTo(MAX_RATIO);

        assertThat(p50ratio)
                .as("p50 latency ratio (exists/not-exists) must be ≤ %.1f×", MAX_RATIO)
                .isLessThanOrEqualTo(MAX_RATIO);
    }

    private long measureNanos(String email) throws Exception {
        long start = System.nanoTime();
        doForgot(email);
        return System.nanoTime() - start;
    }

    private void doForgot(String email) throws Exception {
        mvc.perform(post("/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
    }

    private static long percentile(List<Long> values, int pct) {
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int idx = Math.max(0, (int) Math.ceil(pct / 100.0 * sorted.length) - 1);
        return sorted[idx];
    }
}
