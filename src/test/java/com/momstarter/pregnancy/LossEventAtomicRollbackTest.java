package com.momstarter.pregnancy;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
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
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REAL fail-on-revert test for LOSS-INV-3 (atomicity) — backend-reviewer review item (3),
 * hardened per backend-reviewer round 2 (flaky-suite fix).
 *
 * <p>Unlike {@code LossEventReopenMvcTest#lossEvent_pregnantToEnded_bothProfileAndReminderMutateOnSuccess}
 * (which only asserts the joint SUCCESS-commit state), this test injects a genuine failure
 * INSIDE the {@code @Transactional} method — a {@link ReminderRepository#sweepDeactivateOnLossEvent}
 * throwing a runtime exception — and asserts the WHOLE transaction rolled back: the profile
 * lifecycle stays {@code pregnant} AND {@code loss_date} stays {@code null}. This is the real
 * evidence for LOSS-INV-3: "if any part fails, the whole thing rolls back" — a genuine
 * Spring-managed DB transaction, not a partial/faked atomicity.
 *
 * <h2>Why {@link TestTransaction}, not a bare non-{@code @Transactional} class (round-1 mistake)</h2>
 * <p>The FIRST draft of this test dropped {@code @Transactional} from the class entirely so the
 * service's own transaction would commit/rollback independently of the test's — but that also
 * meant NOTHING here ever auto-rolled-back, so the {@code @AfterEach}
 * {@code users.deleteAll()}/{@code profiles.deleteAll()} teardown had to hard-delete rows from
 * the single H2 instance shared across the WHOLE test JVM run. That teardown made the full
 * suite non-deterministic: backend-reviewer's own repeated full-suite runs (5x) showed 2/5 red,
 * with {@code ConsentMvcTest} — an unrelated, ordinarily-self-cleaning {@code @Transactional}
 * class — occasionally seeing {@code items.length() expected:<1> but was:<0>} depending on
 * interleaving with this class's hard deletes.
 *
 * <p>The class stays {@code @Transactional} like every sibling MVC test, but
 * {@link TestTransaction#flagForCommit()} + {@link TestTransaction#end()} force a REAL commit of
 * this test's own seed data right before the failure-injecting call, so the service's
 * {@code @Transactional} method runs as its own independent transaction (able to really commit
 * or really roll back — the whole point of this test). {@link TestTransaction#start()} then
 * reopens a fresh transaction to read back the true post-call state.
 *
 * <p><strong>Because the seed row was made to commit for real (round-2 fix), the normal
 * end-of-test rollback can NOT clean it up</strong> — a rollback only undoes the SECOND
 * (restarted) transaction, not the first one this test explicitly committed. This class
 * therefore deletes ONLY the exact rows it created, scoped by their own captured ids, in
 * {@code @AfterEach} (FK-safe order: profile before user) — never a blanket {@code deleteAll()}
 * that could race with an unrelated test class's own rows on the shared H2 instance. This is
 * the SAME class of bug that made the round-1 draft flaky (backend-reviewer found 2/5 red
 * full-suite runs, {@code ConsentMvcTest} as the victim via an FK violation on
 * {@code users.deleteAll()}) — narrowly-scoped-by-id deletes close that hole for good.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
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
        user = new User();
        user.setEmail("loss-atomic@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        bearer = jwtService.issueAccessToken(user.getId(), true);
    }

    /**
     * Deletes ONLY the exact rows this test committed for real (scoped by the captured
     * {@link #user} id — never a blanket {@code deleteAll()}), in FK-safe order. Must itself
     * run in a REAL committed transaction: by the time this runs, the test body already left
     * an open, restarted {@link TestTransaction} (from {@link TestTransaction#start()}), and a
     * plain rollback of that transaction would NOT undo the earlier, already-committed seed
     * insert — so this teardown explicitly commits its own deletes.
     */
    @AfterEach
    void cleanup() {
        if (TestTransaction.isActive()) {
            TestTransaction.flagForCommit();
            TestTransaction.end();
        }
        TestTransaction.start();
        profiles.findByUserId(user.getId()).ifPresent(profiles::delete);
        users.findById(user.getId()).ifPresent(users::delete);
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    @Test
    void lossEvent_sweepThrows_wholeTransactionRollsBack_profileStaysPregnant() throws Exception {
        // Create the pregnant profile via the real PUT path (still inside the test's own
        // transaction at this point — fine, this is just seeding).
        mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"" + EDD_STR + "\"}"))
                .andExpect(status().isCreated());

        // Commit the seed data for real so the loss-event call below runs its OWN independent
        // transaction (able to genuinely commit or genuinely roll back) rather than nesting
        // inside — and being masked by — the test's still-open transaction.
        TestTransaction.flagForCommit();
        TestTransaction.end();

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

        // Start a fresh test transaction to read back the REAL, independently-committed-or-
        // rolled-back DB state (not a stale persistence-context view from before).
        TestTransaction.start();

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

        // No manual teardown needed: this test transaction (restarted above) rolls back
        // automatically at test end via the class-level @Transactional, exactly like every
        // sibling MVC test in this codebase — no deleteAll(), no cross-class hazard.
    }
}
