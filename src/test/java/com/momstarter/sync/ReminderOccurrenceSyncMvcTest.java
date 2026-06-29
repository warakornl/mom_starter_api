package com.momstarter.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.occurrence.OccurrenceId;
import com.momstarter.pregnancy.ConsentChecker;
import com.momstarter.reminder.ReminderOccurrence;
import com.momstarter.reminder.ReminderOccurrenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for the {@code reminderOccurrences} sync collection.
 *
 * <p>Covers (api-contract FLAG-7 / N6/N7 / data-model §3.5):
 * <ul>
 *   <li>create occurrence (status=done, correct uuidv5) → applied[]</li>
 *   <li>uuidv5 mismatch → rejected[validation_error]</li>
 *   <li>non-terminal status=due → rejected[validation_error, details:non_terminal_status]</li>
 *   <li>non-terminal status=missed → rejected[validation_error, details:non_terminal_status]</li>
 *   <li>M1 precedence merge: existing=missed, incoming=done, base &lt; current → applied (override)</li>
 *   <li>general_health consent gate → rejected[consent_required]</li>
 *   <li>pull includes reminderOccurrences collection in changes</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class ReminderOccurrenceSyncMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private ReminderOccurrenceRepository occurrences;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    /** Fixed reminder and schedule used throughout tests. */
    private static final UUID REMINDER_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String SCHEDULED_LOCAL_TIME = "2026-07-01T08:00";

    @BeforeEach
    void setup() {
        occurrences.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("occurrence-sync@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // Create — correct uuidv5 + status=done → applied[]
    // -------------------------------------------------------------------------

    @Test
    void push_createOccurrence_done_correctId_applied() throws Exception {
        // Correct id: uuidv5(NAMESPACE, lower(reminderId) + "|" + scheduledLocalTime)
        UUID expectedId = OccurrenceId.compute(
                REMINDER_ID.toString().toLowerCase(), SCHEDULED_LOCAL_TIME);

        Map<String, Object> record = buildOccurrenceRecord(
                expectedId, 0L, REMINDER_ID, SCHEDULED_LOCAL_TIME, "done", Instant.now(), null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("reminderOccurrences"))
                .andExpect(jsonPath("$.applied[0].id").value(expectedId.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        ReminderOccurrence saved = occurrences.findById(expectedId).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getStatus()).isEqualTo("done");
        // reminderId must be stored as lowercase (server normalises)
        assertThat(saved.getReminderId()).isEqualTo(REMINDER_ID);
    }

    // -------------------------------------------------------------------------
    // UUIDv5 mismatch — wrong id in push body → rejected[validation_error]
    // -------------------------------------------------------------------------

    @Test
    void push_wrongOccurrenceId_rejectedValidationError() throws Exception {
        UUID wrongId = UUID.randomUUID(); // not the correct uuidv5

        Map<String, Object> record = buildOccurrenceRecord(
                wrongId, 0L, REMINDER_ID, SCHEDULED_LOCAL_TIME, "done", Instant.now(), null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("reminderOccurrences"))
                .andExpect(jsonPath("$.rejected[0].id").value(wrongId.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Non-terminal status=due → rejected[validation_error, details:non_terminal_status]
    // -------------------------------------------------------------------------

    @Test
    void push_statusDue_rejectedNonTerminalStatus() throws Exception {
        UUID expectedId = OccurrenceId.compute(
                REMINDER_ID.toString().toLowerCase(), SCHEDULED_LOCAL_TIME);

        Map<String, Object> record = buildOccurrenceRecord(
                expectedId, 0L, REMINDER_ID, SCHEDULED_LOCAL_TIME, "due", null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("non_terminal_status"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Non-terminal status=missed → rejected[validation_error, details:non_terminal_status]
    // -------------------------------------------------------------------------

    @Test
    void push_statusMissed_rejectedNonTerminalStatus() throws Exception {
        UUID expectedId = OccurrenceId.compute(
                REMINDER_ID.toString().toLowerCase(), SCHEDULED_LOCAL_TIME);

        Map<String, Object> record = buildOccurrenceRecord(
                expectedId, 0L, REMINDER_ID, SCHEDULED_LOCAL_TIME, "missed", null, null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.rejected[0].details").value("non_terminal_status"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // M1 precedence merge: existing=missed (base version < current), incoming=done → applied
    // -------------------------------------------------------------------------

    @Test
    void push_m1Override_existingMissed_incomingDone_applies() throws Exception {
        UUID id = OccurrenceId.compute(
                REMINDER_ID.toString().toLowerCase(), SCHEDULED_LOCAL_TIME);

        // Seed a "missed" occurrence directly (bypassing the push-reject for missed)
        ReminderOccurrence missed = new ReminderOccurrence();
        missed.setId(id);
        missed.setUserId(userId);
        missed.setReminderId(REMINDER_ID);
        missed.setScheduledLocalTime(LocalDateTime.of(2026, 7, 1, 8, 0));
        missed.setStatus("missed");
        missed = occurrences.saveAndFlush(missed);
        occurrences.initVersionToOne(missed.getId());
        long currentVersion = occurrences.findById(id).orElseThrow().getVersion(); // 1

        // Push a "done" with base version 0 (stale — would normally be server_won)
        // M1 override: done outranks missed regardless of version
        Map<String, Object> record = buildOccurrenceRecord(
                id, 0L, // base version stale (< current)
                REMINDER_ID, SCHEDULED_LOCAL_TIME, "done", Instant.now(), null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(), List.of(record), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("reminderOccurrences"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        // DB status must be "done", not "missed"
        ReminderOccurrence updated = occurrences.findById(id).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("done");
    }

    // -------------------------------------------------------------------------
    // Consent gate — general_health absent → rejected[consent_required]
    // -------------------------------------------------------------------------

    @Test
    void push_generalHealthConsentAbsent_rejectedConsentRequired() throws Exception {
        when(consentChecker.isGranted(eq(userId), eq("cloud_storage"))).thenReturn(true);
        when(consentChecker.isGranted(eq(userId), eq("general_health"))).thenReturn(false);

        UUID id = OccurrenceId.compute(
                REMINDER_ID.toString().toLowerCase(), SCHEDULED_LOCAL_TIME);
        Map<String, Object> record = buildOccurrenceRecord(
                id, 0L, REMINDER_ID, SCHEDULED_LOCAL_TIME, "done", Instant.now(), null);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("reminderOccurrences"))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("general_health"));
    }

    // -------------------------------------------------------------------------
    // Pull — reminderOccurrences collection appears in changes
    // -------------------------------------------------------------------------

    @Test
    void pull_includesReminderOccurrences() throws Exception {
        UUID id = OccurrenceId.compute(
                REMINDER_ID.toString().toLowerCase(), SCHEDULED_LOCAL_TIME);
        ReminderOccurrence occ = new ReminderOccurrence();
        occ.setId(id);
        occ.setUserId(userId);
        occ.setReminderId(REMINDER_ID);
        occ.setScheduledLocalTime(LocalDateTime.of(2026, 7, 1, 8, 0));
        occ.setStatus("done");
        occ.setActedAt(Instant.now());
        occurrences.saveAndFlush(occ);

        MvcResult result = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        var responseNode = objectMapper.readTree(body);
        assertThat(responseNode.at("/changes/reminderOccurrences").isMissingNode()).isFalse();
        var updated = responseNode.at("/changes/reminderOccurrences/updated");
        assertThat(updated.isArray()).isTrue();
        assertThat(updated.size()).isGreaterThan(0);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Map<String, Object> buildOccurrenceRecord(UUID id, long version,
                                                      UUID reminderId, String scheduledLocalTime,
                                                      String status, Instant actedAt,
                                                      Instant snoozedUntil) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id.toString());
        r.put("version", version);
        r.put("reminderId", reminderId.toString());
        r.put("scheduledLocalTime", scheduledLocalTime);
        r.put("status", status);
        r.put("actedAt", actedAt != null ? actedAt.toString() : null);
        r.put("snoozedUntil", snoozedUntil != null ? snoozedUntil.toString() : null);
        r.put("clientId", UUID.randomUUID().toString());
        return r;
    }

    private String buildPushBody(List<Map<String, Object>> created,
                                  List<Map<String, Object>> updated, List<String> deleted)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "reminderOccurrences", Map.of(
                                "created", created,
                                "updated", updated,
                                "deleted", deleted
                        )
                ),
                "lastPulledAt", "0"
        ));
    }
}
