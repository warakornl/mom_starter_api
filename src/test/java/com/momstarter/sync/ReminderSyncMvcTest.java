package com.momstarter.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.ConsentChecker;
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
 * MVC integration tests for the {@code reminders} sync collection.
 *
 * <p>Covers (api-contract FLAG-4 / data-model §3.5):
 * <ul>
 *   <li>create → applied[] (version:=1)</li>
 *   <li>LWW server_won conflict (base &lt; current)</li>
 *   <li>tombstone → applied[]</li>
 *   <li>recurrence-grammar reject: bad freq → rejected[validation_error]</li>
 *   <li>recurrence-grammar reject: one_off with timesOfDay → rejected[validation_error]</li>
 *   <li>recurrence-grammar reject: every_n_days missing interval → rejected[validation_error]</li>
 *   <li>general_health consent gate → rejected[consent_required] via @MockBean</li>
 *   <li>pull includes reminders collection in changes</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class ReminderSyncMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private ReminderRepository reminders;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    @BeforeEach
    void setup() {
        reminders.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("reminder-sync@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);
        // Default: all consents granted
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // Create — new reminder with valid daily rule → applied[]
    // -------------------------------------------------------------------------

    @Test
    void push_createReminder_daily_applied() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of(
                "freq", "daily",
                "timesOfDay", List.of("08:00", "20:00"));
        Map<String, Object> record = buildReminderRecord(id, 0L, "Take vitamins",
                "medication", rule, "2026-07-01T08:00");

        MvcResult result = mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("reminders"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty())
                .andReturn();

        Reminder saved = reminders.findById(id).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getDisplayTitle()).isEqualTo("Take vitamins");
        assertThat(saved.getVersion()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // LWW conflict — base < current → server_won
    // -------------------------------------------------------------------------

    @Test
    void push_staleVersion_serverWonConflict() throws Exception {
        // Seed existing reminder at version 1
        UUID id = UUID.randomUUID();
        Reminder existing = seedReminder(id, "daily", Map.of(
                "freq", "daily",
                "timesOfDay", List.of("08:00")));
        long currentVersion = existing.getVersion(); // 1

        Map<String, Object> rule = Map.of("freq", "daily", "timesOfDay", List.of("08:00"));
        Map<String, Object> record = buildReminderRecord(id, currentVersion - 1, "Stale edit",
                "medication", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(), List.of(record), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts[0].collection").value("reminders"))
                .andExpect(jsonPath("$.conflicts[0].id").value(id.toString()))
                .andExpect(jsonPath("$.conflicts[0].resolution").value("server_won"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Tombstone — delete applied unconditionally
    // -------------------------------------------------------------------------

    @Test
    void push_delete_tombstoned() throws Exception {
        UUID id = UUID.randomUUID();
        seedReminder(id, "daily", Map.of("freq", "daily", "timesOfDay", List.of("08:00")));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(), List.of(),
                                List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("reminders"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        assertThat(reminders.findById(id).orElseThrow().getDeletedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Grammar reject — bad freq
    // -------------------------------------------------------------------------

    @Test
    void push_badFreq_rejectedValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of("freq", "weekly", "timesOfDay", List.of("08:00"));
        Map<String, Object> record = buildReminderRecord(id, 0L, "Bad freq",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("reminders"))
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Grammar reject — one_off with timesOfDay (forbidden per FLAG-4 §a)
    // -------------------------------------------------------------------------

    @Test
    void push_oneOff_withTimesOfDay_rejectedValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of(
                "freq", "one_off",
                "timesOfDay", List.of("08:00")); // FORBIDDEN on one_off
        Map<String, Object> record = buildReminderRecord(id, 0L, "Bad one_off",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Grammar reject — every_n_days without interval
    // -------------------------------------------------------------------------

    @Test
    void push_everyNDays_missingInterval_rejectedValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of(
                "freq", "every_n_days",
                "timesOfDay", List.of("08:00")); // interval absent → invalid
        Map<String, Object> record = buildReminderRecord(id, 0L, "Bad every_n_days",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // =========================================================================
    // Grammar reject — timesOfDay / interval / until regression guards (🟡-1)
    // =========================================================================

    // -------------------------------------------------------------------------
    // Grammar reject — empty timesOfDay
    // -------------------------------------------------------------------------

    @Test
    void push_emptyTimesOfDay_rejectedValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of(
                "freq", "daily",
                "timesOfDay", List.of()); // empty list — forbidden
        Map<String, Object> record = buildReminderRecord(id, 0L, "Empty times",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("reminders"))
                .andExpect(jsonPath("$.rejected[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Grammar reject — unsorted timesOfDay (["20:00","08:00"])
    // -------------------------------------------------------------------------

    @Test
    void push_unsortedTimesOfDay_rejectedValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of(
                "freq", "daily",
                "timesOfDay", List.of("20:00", "08:00")); // descending order — forbidden
        Map<String, Object> record = buildReminderRecord(id, 0L, "Unsorted times",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Grammar reject — duplicate timesOfDay (["08:00","08:00"])
    // -------------------------------------------------------------------------

    @Test
    void push_duplicateTimesOfDay_rejectedValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of(
                "freq", "daily",
                "timesOfDay", List.of("08:00", "08:00")); // duplicate — forbidden
        Map<String, Object> record = buildReminderRecord(id, 0L, "Dup times",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Grammar reject — non-HH:mm format ("8am" — no zero-pad, wrong separator)
    // -------------------------------------------------------------------------

    @Test
    void push_nonHHmmFormat_rejectedValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of(
                "freq", "daily",
                "timesOfDay", List.of("8am")); // "8am" is not HH:mm
        Map<String, Object> record = buildReminderRecord(id, 0L, "Bad format 8am",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Grammar reject — out-of-range HH:mm ("25:00" — hour > 23)
    // -------------------------------------------------------------------------

    @Test
    void push_outOfRangeHHmm_rejectedValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of(
                "freq", "daily",
                "timesOfDay", List.of("25:00")); // hour 25 is outside 00-23
        Map<String, Object> record = buildReminderRecord(id, 0L, "Bad hour 25",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Grammar reject — interval present on daily (interval != 1 is forbidden)
    // -------------------------------------------------------------------------

    @Test
    void push_intervalOnDaily_rejectedValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.<String, Object>of(
                "freq", "daily",
                "timesOfDay", List.of("08:00"),
                "interval", 2); // interval forbidden on daily (value != 1)
        Map<String, Object> record = buildReminderRecord(id, 0L, "Interval on daily",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Grammar reject — interval=0 on every_n_days (must be >= 1)
    // -------------------------------------------------------------------------

    @Test
    void push_intervalZeroOnEveryNDays_rejectedValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.<String, Object>of(
                "freq", "every_n_days",
                "timesOfDay", List.of("08:00"),
                "interval", 0); // interval < 1 → rejected
        Map<String, Object> record = buildReminderRecord(id, 0L, "Zero interval",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Grammar reject — until present on one_off (forbidden per FLAG-4 §a)
    // -------------------------------------------------------------------------

    @Test
    void push_untilOnOneOff_rejectedValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of(
                "freq", "one_off",
                "until", "2026-12-31"); // until is forbidden on one_off
        Map<String, Object> record = buildReminderRecord(id, 0L, "Until on one_off",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // No over-reject — valid every_n_days record is applied
    // -------------------------------------------------------------------------

    @Test
    void push_validEveryNDays_applied() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.<String, Object>of(
                "freq", "every_n_days",
                "timesOfDay", List.of("08:00", "20:00"),
                "interval", 3); // valid: interval >= 1, sorted timesOfDay
        Map<String, Object> record = buildReminderRecord(id, 0L, "Every 3 days",
                "medication", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("reminders"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.rejected").isEmpty())
                .andExpect(jsonPath("$.conflicts").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Consent gate — general_health absent → rejected[consent_required]
    // -------------------------------------------------------------------------

    @Test
    void push_generalHealthConsentAbsent_rejectedConsentRequired() throws Exception {
        // cloud_storage granted; general_health denied
        when(consentChecker.isGranted(eq(userId), eq("cloud_storage"))).thenReturn(true);
        when(consentChecker.isGranted(eq(userId), eq("general_health"))).thenReturn(false);

        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of("freq", "one_off");
        Map<String, Object> record = buildReminderRecord(id, 0L, "No consent",
                "custom", rule, "2026-07-01T08:00");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("reminders"))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("general_health"));
    }

    // -------------------------------------------------------------------------
    // Pull — reminders collection appears in changes
    // -------------------------------------------------------------------------

    @Test
    void pull_includesReminders() throws Exception {
        seedReminder(UUID.randomUUID(), "one_off",
                Map.of("freq", "one_off"));

        MvcResult result = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        var responseNode = objectMapper.readTree(body);
        assertThat(responseNode.has("changes")).isTrue();
        assertThat(responseNode.at("/changes/reminders").isMissingNode()).isFalse();
        var updated = responseNode.at("/changes/reminders/updated");
        assertThat(updated.isArray()).isTrue();
        assertThat(updated.size()).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // Round-trip: push object → pull → recurrenceRule must be a JSON object
    // -------------------------------------------------------------------------

    @Test
    void push_then_pull_roundtrip_recurrenceRuleIsObject() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = Map.of(
                "freq", "daily",
                "timesOfDay", List.of("08:00", "20:00"));
        Map<String, Object> record = buildReminderRecord(id, 0L, "Round-trip daily",
                "medication", rule, "2026-07-01T08:00");

        // 1. Push — must be applied (not rejected)
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected").isEmpty());

        // 2. Pull — recurrenceRule MUST arrive as a nested JSON object, not a string
        mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.reminders.updated[0].recurrenceRule.freq")
                        .value("daily"))
                .andExpect(jsonPath("$.changes.reminders.updated[0].recurrenceRule.timesOfDay[0]")
                        .value("08:00"))
                .andExpect(jsonPath("$.changes.reminders.updated[0].recurrenceRule.timesOfDay[1]")
                        .value("20:00"));
    }

    // -------------------------------------------------------------------------
    // Round-trip: every_n_days + until (ISSUE-6)
    // -------------------------------------------------------------------------

    /**
     * Pushes a reminder with {@code every_n_days} + {@code until} then pulls and asserts
     * that all FLAG-4 grammar fields ({@code freq}, {@code interval}, {@code timesOfDay},
     * {@code until}) survive the round-trip as a JSON object (not a string).
     */
    @Test
    void push_then_pull_roundtrip_everyNDays_with_until() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> rule = new java.util.LinkedHashMap<>();
        rule.put("freq", "every_n_days");
        rule.put("interval", 3);
        rule.put("timesOfDay", List.of("09:00", "21:00"));
        rule.put("until", "2026-12-31");
        Map<String, Object> record = buildReminderRecord(id, 0L, "Every 3 days with until",
                "medication", rule, "2026-07-01T09:00");

        // Push
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody("reminders", List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected").isEmpty());

        // Pull — all four grammar fields must be present as a JSON object
        mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.reminders.updated[0].recurrenceRule.freq")
                        .value("every_n_days"))
                .andExpect(jsonPath("$.changes.reminders.updated[0].recurrenceRule.interval")
                        .value(3))
                .andExpect(jsonPath("$.changes.reminders.updated[0].recurrenceRule.timesOfDay[0]")
                        .value("09:00"))
                .andExpect(jsonPath("$.changes.reminders.updated[0].recurrenceRule.timesOfDay[1]")
                        .value("21:00"))
                .andExpect(jsonPath("$.changes.reminders.updated[0].recurrenceRule.until")
                        .value("2026-12-31"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Map<String, Object> buildReminderRecord(UUID id, long version, String displayTitle,
                                                    String type, Map<String, Object> rule,
                                                    String startAt) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id.toString());
        r.put("version", version);
        r.put("displayTitle", displayTitle);
        r.put("type", type);
        r.put("recurrenceRule", rule);
        r.put("startAt", startAt);
        r.put("active", true);
        r.put("clientId", UUID.randomUUID().toString());
        return r;
    }

    private String buildPushBody(String coll, List<Map<String, Object>> created,
                                  List<Map<String, Object>> updated, List<String> deleted)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        coll, Map.of(
                                "created", created,
                                "updated", updated,
                                "deleted", deleted
                        )
                ),
                "lastPulledAt", "0"
        ));
    }

    /** Seeds a reminder directly into the DB (bypasses push path). */
    private Reminder seedReminder(UUID id, String freq, Map<String, Object> ruleFields)
            throws Exception {
        Reminder r = new Reminder();
        r.setId(id);
        r.setUserId(userId);
        r.setType("custom");
        r.setDisplayTitle("Seeded " + id);
        r.setRecurrenceRule(objectMapper.writeValueAsString(ruleFields));
        r.setStartAt(LocalDateTime.of(2026, 7, 1, 8, 0));
        r.setActive(true);
        r = reminders.saveAndFlush(r);
        reminders.initVersionToOne(r.getId());
        // Reload to get version=1
        return reminders.findById(id).orElseThrow();
    }
}
