package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T-11 — Atomic compare-and-set consume (BE-CORE-6).
 * Two concurrent reset requests with the same token must yield exactly one 204 and one 410.
 * This proves the CAS UPDATE eliminates the TOCTOU window in token consumption.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class ResetPasswordAtomicConsumeTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordResetTokenRepository resetTokens;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private PasswordResetService passwordReset;

    private User seededUser;
    private String resetToken;

    @BeforeEach
    void seed() {
        User u = new User();
        u.setEmail("atomic-" + System.nanoTime() + "@example.com");
        u.setPasswordHash(encoder.encode("oldpassword123"));
        u.setEmailVerified(true);
        seededUser = users.save(u);
        resetToken = passwordReset.issue(u);
    }

    @AfterEach
    void cleanup() {
        // Delete FK-dependent rows first so users.deleteAll() (in other test @BeforeEach) doesn't fail
        if (seededUser != null) {
            resetTokens.findAll().stream()
                    .filter(t -> seededUser.getId().equals(t.getUserId()))
                    .forEach(t -> resetTokens.deleteById(t.getId()));
            users.deleteById(seededUser.getId());
        }
    }

    @Test
    void concurrentDoubleSubmit_exactlyOne204_andOne410() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        String body = "{\"token\":\"" + resetToken + "\",\"newPassword\":\"brandnewpassword!\"}";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return mvc.perform(post("/auth/reset-password")
                                .contentType(APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
            }));
        }

        ready.await();  // both threads are ready
        go.countDown(); // release both simultaneously

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }

        pool.shutdown();

        long count204 = statuses.stream().filter(s -> s == 204).count();
        long count410 = statuses.stream().filter(s -> s == 410).count();

        assertThat(count204)
                .as("Exactly one request must succeed with 204 (statuses: %s)", statuses)
                .isEqualTo(1);
        assertThat(count410)
                .as("Exactly one request must get 410 (statuses: %s)", statuses)
                .isEqualTo(1);
    }
}
