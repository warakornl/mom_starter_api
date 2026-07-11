package com.momstarter.pregnancy;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.reminder.Reminder;
import com.momstarter.reminder.ReminderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REAL fail-on-revert test for LOSS-INV-3 (atomicity) — backend-reviewer review item (3).
 *
 * <p>Unlike {@code LossEventReopenMvcTest#lossEvent_transactionIsAtomic_...} (which only
 * asserts the joint SUCCESS-commit state), this test injects a genuine failure INSIDE the
 * {@code @Transactional} method — a {@link ReminderRepository#sweepDeactivateOnLossEvent}
 * throwing a runtime exception — and asserts the WHOLE transaction rolled back: the profile
 * lifecycle stays {@code pregnant} AND no reminder mutation is observed. This is the real
 * evidence for LOSS-INV-3: "if any part fails, the whole thing rolls back" — a genuine
 * Spring-managed DB transaction, not a partial/faked atomicity.
 *
 * <p>{@link ReminderRepository} is {@code @MockBean}-replaced ONLY in this dedicated test
 * class (kept separate from {@code LossEventReopenMvcTest}, which needs the REAL repository
 * for its DB-state assertions across 28 other scenarios).
 *
 * <p><strong>Deliberately NOT {@code @Transactional} at the class level.</strong> The
 * service's {@code @Transactional} method must run in its OWN, real, independently
 * committing/rolling-back transaction so this test can observe the post-rollback DB state
 * from a separate read — wrapping the test itself in a transaction would make the service
 * call a NESTED participant in the same physical transaction, and an uncommitted
 * {@code saveAndFlush} would still be visible to a same-transaction read-your-own-writes
 * query even though the whole thing is destined to roll back at test end (this exact
 * mistake was caught and fixed during this test's own TDD red/green cycle — the first
 * draft asserted "pregnant" but observed "ended" until {@code @Transactional} was removed
 * from the test class). Cleanup is manual in {@link #seed()}/{@link #cleanup()} instead
 * (a SECOND real bug this test caught: without an explicit {@code @AfterEach} teardown, this
 * class's writes are never rolled back — because H2 here is a single shared in-memory instance
 * across the WHOLE test JVM run (not one-per-class) — and a leftover {@code pregnancy_profile}
 * row blocks an unrelated later test class's {@code users.deleteAll()} via the FK constraint,
 * a real full-suite regression this test introduced and then had to fix).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class LossEventAtomicRollbackTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PregnancyProfileRepository profiles;
    @Autowired
    private JwtService jwtService;

    @MockBean
    private ReminderRepository reminderRepository;

    private User user;
    private String bearer;

    private static final String CLIENT_DATE = "2026-06-29";
    private static final String EDD_STR     = "2026-10-01";

    @BeforeEach
    void seed() {
        profiles.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("loss-atomic@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        bearer = jwtService.issueAccessToken(user.getId(), true);
    }

    /**
     * Explicit teardown — this class has NO {@code @Transactional}, so nothing here
     * auto-rolls-back. Must delete {@code pregnancy_profile} before {@code users} (FK) or a
     * later test class's own {@code users.deleteAll()} fails with a referential-integrity
     * violation against a row this class left behind (see class Javadoc).
     */
    @AfterEach
    void cleanup() {
        profiles.deleteAll();
        users.deleteAll();
    }

    @Test
    void lossEvent_sweepThrows_wholeTransactionRollsBack_profileStaysPregnant() throws Exception {
        // Create the pregnant profile via the real PUT path.
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"" + EDD_STR + "\"}"))
                .andExpect(status().isCreated());

        // Force the reminder sweep (the LAST step inside the @Transactional method) to blow up.
        doThrow(new RuntimeException("simulated sweep failure"))
                .when(reminderRepository).sweepDeactivateOnLossEvent(any(UUID.class), any(Instant.class));

        // The controller has no try/catch for a generic RuntimeException, so Spring's
        // exception resolution surfaces a 5xx — the important assertion is what happens to
        // the DB afterward, not the exact status code of this failed call.
        try {
            mvc.perform(post("/pregnancy-profile/loss-event")
                    .header("Authorization", "Bearer " + bearer)
                    .header("X-Client-Date", CLIENT_DATE)
                    .header("If-Match", "\"0\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"));
        } catch (Exception ignored) {
            // MockMvc may rethrow the unwrapped exception depending on Spring version — either
            // way, the transactional outcome (rolled back or not) is what this test verifies.
        }

        // LOSS-INV-3 fail-on-revert assertion: the profile enum flip must NOT have committed.
        // A real @Transactional rollback means the lifecycle write from the same method body
        // never reached the DB, because the reminder-sweep exception was thrown before COMMIT.
        PregnancyProfile p = profiles.findByUserIdAndDeletedAtIsNull(user.getId()).orElseThrow();
        assertThat(p.getLifecycle())
                .as("profile lifecycle must roll back to 'pregnant' when the reminder sweep throws")
                .isEqualTo("pregnant");
        assertThat(p.getLossDate())
                .as("loss_date must roll back to null when the reminder sweep throws")
                .isNull();
    }
}
