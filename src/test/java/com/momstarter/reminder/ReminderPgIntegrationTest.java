package com.momstarter.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.ConsentChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testcontainers-based PostgreSQL integration test (BLOCKER-1 proof, long-form).
 *
 * <p><b>Status: {@code @Disabled}</b> — Docker Desktop on macOS returns HTTP 400 from its
 * API proxy socket when accessed by the docker-java JVM client (Testcontainers 1.19.8),
 * even though {@code docker ps} works from shell.  This test is preserved as the canonical
 * Testcontainers form; use {@code ReminderLocalPgSmokeTest} for the live PG evidence.
 *
 * @see ReminderLocalPgSmokeTest
 *
 * PostgreSQL integration test for the {@code reminders} collection — proves BLOCKER-1.
 *
 * <p>Runs against a real {@code postgres:16} container (Testcontainers).
 * {@code @ServiceConnection} overrides the datasource URL/user/password so the app
 * connects to the container instead of H2; Flyway migrates the schema automatically.
 *
 * <p>Tests:
 * <ol>
 *   <li><strong>JPA round-trip</strong> — {@link #jpa_saveAndLoad_recurrenceRule_doesNotThrowOnPg}:
 *       directly saves a {@link Reminder} with a JSON-string {@code recurrenceRule} via the
 *       JPA repository and loads it back.  Without {@code @JdbcTypeCode(SqlTypes.JSON)} this
 *       throws {@code ERROR: column "recurrence_rule" is of type jsonb but expression is of
 *       type character varying} on real PostgreSQL.</li>
 *   <li><strong>Sync push → pull round-trip</strong> — {@link #push_then_pull_recurrenceRuleIsObjectOnPg}:
 *       pushes a reminder via {@code POST /sync/push} with a nested JSON object, then
 *       asserts that {@code GET /sync/pull} returns {@code recurrenceRule} as a JSON object
 *       (not a string), proving that {@code toRecord()} deserialises the jsonb value correctly
 *       when read back from real PostgreSQL.</li>
 * </ol>
 */
@Disabled("Testcontainers: Docker Desktop returns HTTP 400 from its API proxy for JVM clients "
        + "(Testcontainers 1.19.8 / Docker Desktop 4.79.0). "
        + "See ReminderLocalPgSmokeTest for the live PostgreSQL proof of BLOCKER-1.")
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "momstarter.ratelimit.login-per-ip-per-min=1000000",
        "momstarter.ratelimit.register-per-ip-per-min=1000000",
        "momstarter.ratelimit.resend-per-ip-per-min=1000000",
        "momstarter.ratelimit.forgot-per-ip-per-min=1000000",
        "momstarter.ratelimit.reset-per-ip-per-min=1000000",
        "momstarter.ratelimit.verify-email-per-ip-per-min=1000000",
        "momstarter.dev.auto-verify-email=false"
})
@Transactional
class ReminderPgIntegrationTest {

    /**
     * Testcontainers-managed postgres:16 container.
     * {@code @ServiceConnection} registers a {@code PostgreSqlConnectionDetails} bean that
     * Spring Boot uses to override the datasource URL, username, and password, so the
     * application connects to this container instead of any profile-configured datasource.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16");

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private ReminderRepository reminders;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @MockBean
    private ConsentChecker consentChecker;

    private UUID userId;
    private String bearer;

    @BeforeEach
    void setup() {
        reminders.deleteAll();
        users.deleteAll();
        User user = new User();
        user.setEmail("pg-smoke@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // JPA round-trip: directly save + reload via repository (proves INSERT binding)
    // -------------------------------------------------------------------------

    /**
     * Saves a {@link Reminder} with a JSON-string {@code recurrenceRule} directly via JPA,
     * then reloads it.  Proves that {@code @JdbcTypeCode(SqlTypes.JSON)} on the field causes
     * Hibernate to bind the parameter as {@code jsonb} rather than {@code character varying}.
     *
     * <p>Without the annotation this test throws:
     * <pre>ERROR: column "recurrence_rule" is of type jsonb but
     *        expression is of type character varying
     *        Hint: You will need to rewrite or cast the expression.</pre>
     */
    @Test
    void jpa_saveAndLoad_recurrenceRule_doesNotThrowOnPg() {
        UUID id = UUID.randomUUID();
        Reminder r = new Reminder();
        r.setId(id);
        r.setUserId(userId);
        r.setType("custom");
        r.setDisplayTitle("PG smoke");
        r.setRecurrenceRule("{\"freq\":\"daily\",\"timesOfDay\":[\"08:00\"]}");
        r.setStartAt(LocalDateTime.of(2026, 7, 1, 8, 0));
        r.setActive(true);

        // This line throws on PG without @JdbcTypeCode(SqlTypes.JSON)
        Reminder saved = reminders.saveAndFlush(r);
        reminders.initVersionToOne(saved.getId());

        Reminder loaded = reminders.findById(id).orElseThrow();
        // recurrenceRule round-trips correctly: raw JSON string (PG returns it as JSON text)
        assertThat(loaded.getRecurrenceRule()).contains("\"freq\"");
        assertThat(loaded.getRecurrenceRule()).contains("daily");
    }

    // -------------------------------------------------------------------------
    // Full sync round-trip: push object → pull → recurrenceRule must be a JSON object
    // -------------------------------------------------------------------------

    /**
     * Pushes a reminder via {@code POST /sync/push} (recurrenceRule as nested JSON object),
     * then pulls via {@code GET /sync/pull} and asserts the {@code recurrenceRule} field
     * in the response is a JSON object with the correct {@code freq} and {@code timesOfDay}
     * values — not a string literal.
     *
     * <p>This is the primary BLOCKER-1 proof: H2 is lenient about varchar→jsonb; PostgreSQL
     * is not.  The Testcontainers container is the real PostgreSQL 16 engine.
     */
    @Test
    void push_then_pull_recurrenceRuleIsObjectOnPg() throws Exception {
        UUID id = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "reminders", Map.of(
                                "created", List.of(Map.of(
                                        "id", id.toString(),
                                        "version", 0,
                                        "displayTitle", "PG round-trip",
                                        "type", "medication",
                                        "recurrenceRule", Map.of(
                                                "freq", "daily",
                                                "timesOfDay", List.of("08:00", "20:00")),
                                        "startAt", "2026-07-01T08:00",
                                        "active", true,
                                        "clientId", UUID.randomUUID().toString()
                                )),
                                "updated", List.of(),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        // Push — must be applied (no varchar→jsonb error on PG)
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected").isEmpty());

        // Pull — recurrenceRule MUST be a JSON object (not a string) on PG
        MvcResult pullResult = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode pullBody = objectMapper.readTree(pullResult.getResponse().getContentAsString());
        JsonNode updated = pullBody.at("/changes/reminders/updated");
        assertThat(updated.isArray()).isTrue();
        assertThat(updated.size()).isGreaterThan(0);

        JsonNode rr = updated.get(0).get("recurrenceRule");
        // Must be an object (ObjectNode), not a string (TextNode)
        assertThat(rr.isObject())
                .as("recurrenceRule must be a JSON object on PG, got: " + rr)
                .isTrue();
        assertThat(rr.get("freq").asText()).isEqualTo("daily");
        assertThat(rr.get("timesOfDay").get(0).asText()).isEqualTo("08:00");
        assertThat(rr.get("timesOfDay").get(1).asText()).isEqualTo("20:00");
    }

    // -------------------------------------------------------------------------
    // Mark occurrence done on PG (exercises the occurrence path end-to-end)
    // -------------------------------------------------------------------------

    /**
     * Pushes a reminder, then pushes a {@code done} occurrence for that reminder.
     * Proves the full W-A-sparse reminder+occurrence write path on real PostgreSQL.
     */
    @Test
    void push_reminderThenOccurrence_donePersistsOnPg() throws Exception {
        UUID reminderId = UUID.randomUUID();
        String pushBody = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "reminders", Map.of(
                                "created", List.of(Map.of(
                                        "id", reminderId.toString(),
                                        "version", 0,
                                        "displayTitle", "Vitamins PG",
                                        "type", "medication",
                                        "recurrenceRule", Map.of(
                                                "freq", "daily",
                                                "timesOfDay", List.of("08:00")),
                                        "startAt", "2026-07-01T08:00",
                                        "active", true,
                                        "clientId", UUID.randomUUID().toString()
                                )),
                                "updated", List.of(),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        // Push reminder
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pushBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(reminderId.toString()));

        // Verify saved in PG
        Reminder saved = reminders.findById(reminderId).orElseThrow();
        assertThat(saved.getDisplayTitle()).isEqualTo("Vitamins PG");
        assertThat(saved.getRecurrenceRule()).contains("daily");
    }
}
