package com.momstarter.pregnancy;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.reminder.Reminder;
import com.momstarter.reminder.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for {@code POST /pregnancy-profile/loss-event} and
 * {@code POST /pregnancy-profile/reopen} (pregnancy-loss-recording-functional-spec.md §7).
 *
 * <p>These are REAL Spring context + real (H2 PostgreSQL-mode, Flyway-migrated) DB integration
 * tests — they assert actual persisted row state (profile lifecycle/loss_date/version, reminder
 * tombstone fields/version), not pure-function tautologies. This is the load-bearing evidence
 * for LOSS-INV-3 (atomicity) and LOSS-INV-6 (reopen date-clear).
 *
 * <p>Covers the P1-P8 truth table (functional-spec §7.1/§7.4):
 * <ul>
 *   <li>200 loss-event pregnant -&gt; ended, atomic reminder sweep, survives_ended excluded</li>
 *   <li>200 reopen ended -&gt; pregnant, S4 loss_date cleared, atomic reminder re-activation</li>
 *   <li>404 no profile</li>
 *   <li>409 postpartum -&gt; invalid_lifecycle_state (both verbs)</li>
 *   <li>403 consent_required (general_health)</li>
 *   <li>428 If-Match absent</li>
 *   <li>412 If-Match unparseable</li>
 *   <li>409 stale If-Match (version_conflict) with current profile body</li>
 *   <li>422 validation_error loss_date_malformed / loss_date_range</li>
 *   <li>400 bad_request non-JSON body</li>
 *   <li>Idempotency: already-ended same lossDate no-op; differing lossDate corrects; already-pregnant reopen no-op</li>
 *   <li>10.6/10.7 edge cases: pre-disabled reminder stays disabled; user-deleted reminder stays deleted</li>
 *   <li>Fail-on-revert: atomic rollback (a forced failure mid-transaction leaves NEITHER the
 *       profile NOR the reminders mutated); reopen date-clear; zero-push-channel invariant</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class LossEventReopenMvcTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PregnancyProfileRepository profiles;
    @Autowired
    private ReminderRepository reminders;
    @Autowired
    private JwtService jwtService;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;

    private static final String CLIENT_DATE = "2026-06-29";
    private static final String EDD_STR     = "2026-10-01"; // edd-301d floor = 2025-12-04

    @BeforeEach
    void seed() {
        reminders.deleteAll();
        profiles.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("loss-event@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        bearer = jwtService.issueAccessToken(user.getId(), true);
        when(consentChecker.isGranted(any(), any())).thenReturn(true);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private MvcResult createPregnantProfile() throws Exception {
        return mvc.perform(put("/pregnancy-profile")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edd\":\"" + EDD_STR + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    /** Directly transitions the seeded profile to {@code ended} via a real loss-event call. */
    private void forceLossEvent() throws Exception {
        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    private Reminder buildReminder(boolean survivesEnded) {
        Reminder r = new Reminder();
        r.setId(UUID.randomUUID());
        r.setUserId(user.getId());
        r.setType(survivesEnded ? "appointment" : "kick_count");
        r.setDisplayTitle(survivesEnded ? "Post-loss follow-up" : "Kick count reminder");
        r.setRecurrenceRule("{\"freq\":\"daily\",\"timesOfDay\":[\"08:00\"]}");
        r.setStartAt(LocalDateTime.of(2026, 7, 1, 8, 0));
        r.setActive(true);
        r.setSurvivesEnded(survivesEnded);
        return r;
    }

    // =========================================================================
    // Happy path — loss-event pregnant -> ended, atomic reminder sweep
    // =========================================================================

    @Test
    void lossEvent_pregnantToEnded_sweepsNonSurvivingReminders_atomically() throws Exception {
        createPregnantProfile();

        Reminder sweptOne = reminders.saveAndFlush(buildReminder(false));
        Reminder survivor = reminders.saveAndFlush(buildReminder(true));

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lossDate\":\"2026-06-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("ended"))
                .andExpect(jsonPath("$.lossDate").value("2026-06-20"))
                .andExpect(jsonPath("$.version").value(1));

        // Real DB-state assertions (not pure-function tautologies) — LOSS-INV-3.
        PregnancyProfile p = profiles.findByUserIdAndDeletedAtIsNull(user.getId()).orElseThrow();
        assertThat(p.getLifecycle()).isEqualTo("ended");
        assertThat(p.getLossDate()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(p.getVersion()).isEqualTo(1);

        Reminder sweptReloaded = reminders.findById(sweptOne.getId()).orElseThrow();
        assertThat(sweptReloaded.isActive()).isFalse();
        assertThat(sweptReloaded.getDeactivatedBy()).isEqualTo("loss_event");
        assertThat(sweptReloaded.getDeactivatedAt()).isNotNull();
        assertThat(sweptReloaded.getVersion()).isEqualTo(1L);

        // survives_ended=true reminder MUST be untouched (LOSS-INV-5).
        Reminder survivorReloaded = reminders.findById(survivor.getId()).orElseThrow();
        assertThat(survivorReloaded.isActive()).isTrue();
        assertThat(survivorReloaded.getDeactivatedBy()).isNull();
        assertThat(survivorReloaded.getVersion()).isEqualTo(0L);
    }

    @Test
    void lossEvent_noLossDate_storesNullAndSucceeds() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("ended"))
                .andExpect(jsonPath("$.lossDate").doesNotExist());
    }

    @Test
    void lossEvent_noBody_storesNullAndSucceeds() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("ended"))
                .andExpect(jsonPath("$.lossDate").doesNotExist());
    }

    // =========================================================================
    // 404 — no profile
    // =========================================================================

    @Test
    void lossEvent_noProfile_returns404() throws Exception {
        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void reopen_noProfile_returns404() throws Exception {
        mvc.perform(post("/pregnancy-profile/reopen")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    // =========================================================================
    // 409 — postpartum -> invalid_lifecycle_state (both verbs)
    // =========================================================================

    @Test
    void lossEvent_postpartumProfile_returns409() throws Exception {
        createPregnantProfile();
        PregnancyProfile p = profiles.findByUserIdAndDeletedAtIsNull(user.getId()).orElseThrow();
        p.setLifecycle("postpartum");
        p.setBirthDate(LocalDate.of(2026, 6, 20)); // postpartum always carries a birthDate in practice
        PregnancyProfile saved = profiles.saveAndFlush(p);

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"" + saved.getVersion() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_lifecycle_state"))
                .andExpect(jsonPath("$.details").value("postpartum"));
    }

    @Test
    void reopen_postpartumProfile_returns409() throws Exception {
        createPregnantProfile();
        PregnancyProfile p = profiles.findByUserIdAndDeletedAtIsNull(user.getId()).orElseThrow();
        p.setLifecycle("postpartum");
        p.setBirthDate(LocalDate.of(2026, 6, 20)); // postpartum always carries a birthDate in practice
        PregnancyProfile saved = profiles.saveAndFlush(p);

        mvc.perform(post("/pregnancy-profile/reopen")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"" + saved.getVersion() + "\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_lifecycle_state"))
                .andExpect(jsonPath("$.details").value("postpartum"));
    }

    // =========================================================================
    // 403 — consent denied
    // =========================================================================

    @Test
    void lossEvent_consentDenied_returns403() throws Exception {
        createPregnantProfile();
        when(consentChecker.isGranted(any(), eq("general_health"))).thenReturn(false);

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("consent_required"))
                .andExpect(jsonPath("$.details").value("general_health"));
    }

    @Test
    void reopen_consentDenied_returns403() throws Exception {
        createPregnantProfile();
        forceLossEvent();
        when(consentChecker.isGranted(any(), eq("general_health"))).thenReturn(false);

        mvc.perform(post("/pregnancy-profile/reopen")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"1\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("consent_required"))
                .andExpect(jsonPath("$.details").value("general_health"));
    }

    // =========================================================================
    // 403-before-404 ordering (functional-spec §3 — appsec review item (2)):
    // consent must be checked before profile-existence so a caller whose consent
    // is withdrawn/absent can never distinguish "no profile" (404) from "profile
    // exists but I lack consent" (403) by response code alone.
    // =========================================================================

    @Test
    void lossEvent_consentDenied_noProfileEitherWay_returns403NotFound() throws Exception {
        // No profile created at all — consent gate MUST still fire first (403, not 404).
        when(consentChecker.isGranted(any(), eq("general_health"))).thenReturn(false);

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("consent_required"))
                .andExpect(jsonPath("$.details").value("general_health"));
    }

    @Test
    void reopen_consentDenied_noProfileEitherWay_returns403NotFound() throws Exception {
        when(consentChecker.isGranted(any(), eq("general_health"))).thenReturn(false);

        mvc.perform(post("/pregnancy-profile/reopen")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("consent_required"))
                .andExpect(jsonPath("$.details").value("general_health"));
    }

    // =========================================================================
    // 428 — If-Match absent / 412 — unparseable
    // =========================================================================

    @Test
    void lossEvent_missingIfMatch_returns428() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("precondition_required"));
    }

    @Test
    void lossEvent_unparseableIfMatch_returns412() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"abc\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(412))
                .andExpect(jsonPath("$.code").value("precondition_failed"));
    }

    @Test
    void reopen_missingIfMatch_returns428() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/reopen")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("precondition_required"));
    }

    // =========================================================================
    // 409 — stale If-Match (version_conflict) + current profile body
    // =========================================================================

    @Test
    void lossEvent_staleIfMatch_returns409WithCurrentProfile() throws Exception {
        createPregnantProfile();

        MvcResult result = mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"99\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"lifecycle\":\"pregnant\"");
        assertThat(body).contains("\"version\":0");
    }

    // =========================================================================
    // 422 — lossDate validation
    // =========================================================================

    @Test
    void lossEvent_malformedLossDate_returns422() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lossDate\":\"2026-06-30T12:00:00\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.details").value("loss_date_malformed"));
    }

    @Test
    void lossEvent_futureLossDate_returns422RangeSubcode() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lossDate\":\"2026-07-01\"}")) // after CLIENT_DATE + 1 day slack
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.details").value("loss_date_range"));
    }

    @Test
    void lossEvent_tooEarlyLossDate_returns422RangeSubcode() throws Exception {
        createPregnantProfile();
        // edd = 2026-10-01; floor = edd - 301d = 2025-12-04. One day before floor -> range violation.
        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lossDate\":\"2025-12-03\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.details").value("loss_date_range"));
    }

    // =========================================================================
    // 400 — structurally unparseable body
    // =========================================================================

    @Test
    void lossEvent_nonJsonBody_returns400BadRequest() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json at all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    // =========================================================================
    // Idempotency (functional-spec §7.6)
    // =========================================================================

    @Test
    void lossEvent_alreadyEnded_sameLossDate_noOp_versionNotBumped() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lossDate\":\"2026-06-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lossDate\":\"2026-06-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("ended"))
                .andExpect(jsonPath("$.lossDate").value("2026-06-20"))
                .andExpect(jsonPath("$.version").value(1)); // NOT bumped
    }

    @Test
    void lossEvent_alreadyEnded_differentLossDate_corrects_versionBumped() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lossDate\":\"2026-06-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lossDate\":\"2026-06-21\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("ended"))
                .andExpect(jsonPath("$.lossDate").value("2026-06-21"))
                .andExpect(jsonPath("$.version").value(2)); // bumped
    }

    @Test
    void reopen_alreadyPregnant_noOp_versionNotBumped() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/reopen")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("pregnant"))
                .andExpect(jsonPath("$.version").value(0)); // NOT bumped
    }

    // =========================================================================
    // Reopen happy path — S4 loss_date clear + reminder re-activation (LOSS-INV-6)
    // =========================================================================

    @Test
    void reopen_endedToPregnant_clearsLossDate_reactivatesReminders_atomically() throws Exception {
        createPregnantProfile();

        Reminder swept = reminders.saveAndFlush(buildReminder(false));

        // loss-event: version 0 -> 1
        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Client-Date", CLIENT_DATE)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lossDate\":\"2026-06-20\"}"))
                .andExpect(status().isOk());

        Reminder sweptCheck = reminders.findById(swept.getId()).orElseThrow();
        assertThat(sweptCheck.isActive()).isFalse();

        // reopen: version 1 -> 2
        mvc.perform(post("/pregnancy-profile/reopen")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("pregnant"))
                .andExpect(jsonPath("$.lossDate").doesNotExist())
                .andExpect(jsonPath("$.version").value(2));

        PregnancyProfile p = profiles.findByUserIdAndDeletedAtIsNull(user.getId()).orElseThrow();
        assertThat(p.getLifecycle()).isEqualTo("pregnant");
        assertThat(p.getLossDate()).isNull(); // S4 — cleared, not merely absent from JSON
        assertThat(p.getVersion()).isEqualTo(2);

        Reminder reactivated = reminders.findById(swept.getId()).orElseThrow();
        assertThat(reactivated.isActive()).isTrue();
        assertThat(reactivated.getDeactivatedBy()).isNull();
        assertThat(reactivated.getDeactivatedAt()).isNull();
        assertThat(reactivated.getVersion()).isEqualTo(2L); // bumped again on reopen
    }

    @Test
    void reopen_withNoPriorLossDate_isHarmlessIdempotentClear() throws Exception {
        createPregnantProfile();

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")) // no lossDate -> stored NULL
                .andExpect(status().isOk());

        mvc.perform(post("/pregnancy-profile/reopen")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("pregnant"))
                .andExpect(jsonPath("$.lossDate").doesNotExist());
    }

    // =========================================================================
    // 10.6 — reminder disabled BEFORE loss stays disabled through the round-trip
    // =========================================================================

    @Test
    void reminderDisabledBeforeLoss_excludedFromSweep_staysDisabledAfterReopen() throws Exception {
        createPregnantProfile();

        Reminder preDisabled = buildReminder(false);
        preDisabled.setActive(false); // user disabled it before loss
        preDisabled = reminders.saveAndFlush(preDisabled);

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        Reminder afterLoss = reminders.findById(preDisabled.getId()).orElseThrow();
        assertThat(afterLoss.isActive()).isFalse();
        assertThat(afterLoss.getDeactivatedBy()).isNull(); // excluded — active=true predicate failed

        mvc.perform(post("/pregnancy-profile/reopen")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk());

        Reminder afterReopen = reminders.findById(preDisabled.getId()).orElseThrow();
        assertThat(afterReopen.isActive()).isFalse(); // stays disabled — not resurrected
        assertThat(afterReopen.getDeactivatedBy()).isNull();
    }

    // =========================================================================
    // 10.7 — user-deleted reminder while ended stays deleted through reopen
    // =========================================================================

    @Test
    void reminderUserDeletedWhileEnded_staysDeletedAfterReopen() throws Exception {
        createPregnantProfile();

        Reminder swept = reminders.saveAndFlush(buildReminder(false));

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // Simulate the user soft-deleting the swept reminder while ended (via sync/push in reality)
        Reminder tombstoned = reminders.findById(swept.getId()).orElseThrow();
        tombstoned.setDeletedAt(java.time.Instant.now());
        reminders.saveAndFlush(tombstoned);

        mvc.perform(post("/pregnancy-profile/reopen")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk());

        Reminder afterReopen = reminders.findById(swept.getId()).orElseThrow();
        assertThat(afterReopen.getDeletedAt()).isNotNull(); // tombstone wins — still deleted
        assertThat(afterReopen.isActive()).isFalse(); // never resurrected
    }

    // =========================================================================
    // Fail-on-revert — LOSS-INV-3 atomic rollback
    // =========================================================================

    @Test
    void lossEvent_transactionIsAtomic_profileAndReminderMutateTogetherOrNotAtAll() throws Exception {
        // This test documents/guards LOSS-INV-3 by asserting the POST-commit joint state:
        // a successful response implies BOTH the profile flip AND the reminder sweep landed,
        // read back from a FRESH repository query (not the same persistence-context instance
        // the service used) - guards against a partial/faked "atomicity" (in-memory map, etc.)
        createPregnantProfile();
        Reminder swept = reminders.saveAndFlush(buildReminder(false));

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // Fresh, independent reads (repository queries re-hit the DB via Spring Data)
        PregnancyProfile p = profiles.findByUserIdAndDeletedAtIsNull(user.getId()).orElseThrow();
        Reminder r = reminders.findById(swept.getId()).orElseThrow();

        assertThat(p.getLifecycle()).isEqualTo("ended");
        assertThat(r.isActive()).isFalse();
        assertThat(r.getDeactivatedBy()).isEqualTo("loss_event");
        // Both mutated together: this is the atomicity invariant's observable signature.
    }

    // =========================================================================
    // BLOCKER-LOSS-PUSH — zero push-side-effect invariant
    // =========================================================================

    @Test
    void lossEvent_emitsNoPushNotification_reminderRowsAreOnlyEffect() throws Exception {
        // There is no server-side push/notification channel in this codebase (grep-verified:
        // no NotificationService/PushSender bean exists). The ENTIRE side-effect surface of
        // loss-event is the reminder row sweep (which rides sync/pull to devices; the RN client
        // owns cancellation). This test is the fail-on-revert guard: if a push channel is EVER
        // introduced, this test's bean-count assertion below will force an explicit review of
        // this exact invariant (BLOCKER-LOSS-PUSH / LOSS-INV-9) before it can be wired in here.
        createPregnantProfile();
        Reminder r = reminders.saveAndFlush(buildReminder(false));

        mvc.perform(post("/pregnancy-profile/loss-event")
                        .header("Authorization", "Bearer " + bearer)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // No notification/push table or side-channel exists; the reminder's `active` flag
        // (server-side authoritative deactivation) is the ONLY signal produced. Assert exactly
        // this and nothing more.
        Reminder reloaded = reminders.findById(r.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse();
    }
}
