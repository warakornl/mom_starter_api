package com.momstarter.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.ConsentChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.net.Socket;
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
 * <strong>BLOCKER-1 live proof</strong> — runs against the real {@code postgres:16} container
 * ({@code momstarter-pg}, already started via {@code docker run} / {@code docker-compose}).
 *
 * <p>Testcontainers cannot reach the Docker Desktop socket from the JVM subprocess in this
 * environment (Docker Desktop 4.79.0 / macOS returns HTTP 400 to the docker-java client).
 * This test uses the <em>manual docker-run</em> fallback described in the task brief:
 * point the Spring datasource at {@code localhost:5432} (the already-running container)
 * via {@link TestPropertySource}, override the H2 URL from {@code application-test.yml},
 * and let Flyway skip already-applied migrations.
 *
 * <h3>Evidence this test provides</h3>
 * <ul>
 *   <li>{@link #jpa_saveFlush_recurrenceRule_doesNotThrowOnPg} — direct repository save on PG.
 *       Without {@code @JdbcTypeCode(SqlTypes.JSON)} on {@code Reminder.recurrenceRule} this
 *       throws {@code PSQLException: ERROR: column "recurrence_rule" is of type jsonb but
 *       expression is of type character varying}.</li>
 *   <li>{@link #push_then_pull_recurrenceRule_isJsonObjectOnPg} — full sync push→pull on PG,
 *       asserts {@code recurrenceRule} in the pull response is a JSON object (not a string).</li>
 * </ul>
 *
 * <h3>Pre-condition</h3>
 * <p>{@code docker run -d --name momstarter-pg -p 5432:5432
 * -e POSTGRES_DB=momstarter -e POSTGRES_USER=momstarter -e POSTGRES_PASSWORD=momstarter
 * postgres:16} (already running when this test runs in the dev environment).
 *
 * <h3>Transaction isolation</h3>
 * <p>{@code @Transactional} rolls back every test method's writes.  {@code @BeforeEach}
 * does NOT call {@code deleteAll()} to avoid FK-ordering issues on real PostgreSQL;
 * instead each test creates a unique user UUID, so data from other tests / runs is invisible.
 *
 * <h3>Guard — no PG = SKIP, not ERROR</h3>
 * <p>{@code @EnabledIf("pgReachable")} causes JUnit 5 to evaluate {@link #pgReachable()}
 * as an {@code ExecutionCondition} before {@code SpringExtension.beforeAll()} attempts to
 * load the ApplicationContext.  When {@code localhost:5432} is unreachable the entire class
 * is marked DISABLED (skipped), so {@code mvn test} stays green on a clean checkout with
 * no Docker container running.
 */
@EnabledIf("pgReachable")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Override application-test.yml's H2 URL with the running PG container.
        // @TestPropertySource has higher priority than profile properties.
        "spring.datasource.url=jdbc:postgresql://localhost:5432/momstarter",
        "spring.datasource.username=momstarter",
        "spring.datasource.password=momstarter",
        "spring.jpa.hibernate.ddl-auto=none",
        // Flyway safe: already-applied migrations are skipped via flyway_schema_history.
        "spring.flyway.enabled=true",
        // Disable DevModeGuard (application-local.yml sets this to true; we must NOT).
        "momstarter.dev.auto-verify-email=false",
        // High limits so MVC tests don't hit the rate limiter
        "momstarter.ratelimit.login-per-ip-per-min=1000000",
        "momstarter.ratelimit.register-per-ip-per-min=1000000",
        "momstarter.ratelimit.resend-per-ip-per-min=1000000",
        "momstarter.ratelimit.forgot-per-ip-per-min=1000000",
        "momstarter.ratelimit.reset-per-ip-per-min=1000000",
        "momstarter.ratelimit.verify-email-per-ip-per-min=1000000"
})
@Transactional
class ReminderLocalPgSmokeTest {

    /**
     * Probed by {@code @EnabledIf("pgReachable")} before Spring loads the ApplicationContext.
     * Attempts a TCP connect to {@code localhost:5432} with a 1.5-second timeout.
     * Returns {@code true} when PostgreSQL is reachable; {@code false} otherwise (container
     * absent or port closed), causing JUnit to mark the entire class as SKIPPED.
     */
    static boolean pgReachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 5432), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private ReminderRepository reminders;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @MockBean
    private ConsentChecker consentChecker;

    private UUID userId;
    private String bearer;

    /**
     * Creates a fresh user per test.  We do NOT call deleteAll() here to avoid FK
     * violation ordering problems on real PostgreSQL; @Transactional rollback handles
     * cleanup automatically.
     */
    @BeforeEach
    void setup() {
        User user = new User();
        user.setEmail("pg-smoke-" + UUID.randomUUID() + "@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // JPA round-trip: proves INSERT binding on PG (BLOCKER-1 core)
    // -------------------------------------------------------------------------

    /**
     * Saves a {@link Reminder} with a JSON-string {@code recurrenceRule} directly via the
     * JPA repository and flushes to the real PostgreSQL.
     *
     * <p>Without {@code @JdbcTypeCode(SqlTypes.JSON)} on {@code Reminder.recurrenceRule}
     * this throws:
     * <pre>
     * org.postgresql.util.PSQLException: ERROR: column "recurrence_rule" is of type jsonb
     * but expression is of type character varying
     * Hint: You will need to rewrite or cast the expression.
     * </pre>
     *
     * <p>With the annotation, Hibernate uses the JSON JDBC type for binding and the INSERT
     * succeeds.
     */
    @Test
    void jpa_saveFlush_recurrenceRule_doesNotThrowOnPg() {
        UUID id = UUID.randomUUID();
        Reminder r = new Reminder();
        r.setId(id);
        r.setUserId(userId);
        r.setType("custom");
        r.setDisplayTitle("PG BLOCKER-1 smoke");
        r.setRecurrenceRule("{\"freq\":\"daily\",\"timesOfDay\":[\"08:00\"]}");
        r.setStartAt(LocalDateTime.of(2026, 7, 1, 8, 0));
        r.setActive(true);

        // This line is the BLOCKER-1 test. Without @JdbcTypeCode(SqlTypes.JSON) on the
        // Reminder.recurrenceRule field, Hibernate binds a varchar to a jsonb column and
        // PostgreSQL rejects the INSERT with "expression is of type character varying".
        Reminder saved = reminders.saveAndFlush(r);
        reminders.initVersionToOne(saved.getId());

        // Reload from PG — round-trips correctly as a JSON string
        Reminder loaded = reminders.findById(id).orElseThrow();
        assertThat(loaded.getRecurrenceRule()).contains("\"freq\"");
        assertThat(loaded.getRecurrenceRule()).contains("daily");
        assertThat(loaded.getDisplayTitle()).isEqualTo("PG BLOCKER-1 smoke");
    }

    // -------------------------------------------------------------------------
    // Full sync push→pull: proves toRecord() deserialises correctly on PG
    // -------------------------------------------------------------------------

    /**
     * Pushes a reminder via {@code POST /sync/push} with {@code recurrenceRule} as a nested
     * JSON object, then pulls via {@code GET /sync/pull} and asserts the response contains
     * a JSON object (not a string) for {@code recurrenceRule}.
     *
     * <p>H2 in PostgreSQL MODE coerces varchar silently into jsonb; real PostgreSQL does not.
     * This test running on real PostgreSQL is the definitive proof that BLOCKER-1 is closed.
     */
    @Test
    void push_then_pull_recurrenceRule_isJsonObjectOnPg() throws Exception {
        UUID id = UUID.randomUUID();
        String pushBody = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "reminders", Map.of(
                                "created", List.of(Map.of(
                                        "id", id.toString(),
                                        "version", 0,
                                        "displayTitle", "PG round-trip daily",
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

        // Push — must be applied without PSQLException: varchar→jsonb type mismatch
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pushBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected").isEmpty());

        // Pull — recurrenceRule must be a JSON object on PG, not a raw string
        MvcResult pullResult = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode pullBody = objectMapper.readTree(pullResult.getResponse().getContentAsString());
        JsonNode updated = pullBody.at("/changes/reminders/updated");
        assertThat(updated.isArray()).isTrue();
        assertThat(updated.size()).isGreaterThan(0);

        JsonNode rr = updated.get(0).get("recurrenceRule");
        assertThat(rr.isObject())
                .as("Expected ObjectNode from PG jsonb column, got nodeType=%s value=%s",
                        rr.getNodeType(), rr)
                .isTrue();
        assertThat(rr.get("freq").asText()).isEqualTo("daily");
        assertThat(rr.get("timesOfDay").get(0).asText()).isEqualTo("08:00");
        assertThat(rr.get("timesOfDay").get(1).asText()).isEqualTo("20:00");
    }

    // -------------------------------------------------------------------------
    // every_n_days + until round-trip on PG (ISSUE-6)
    // -------------------------------------------------------------------------

    /**
     * Pushes an {@code every_n_days} reminder with {@code until} and verifies all four
     * FLAG-4 grammar fields survive the push→pull round-trip on real PostgreSQL.
     */
    @Test
    void push_then_pull_everyNDaysWith_until_onPg() throws Exception {
        UUID id = UUID.randomUUID();
        String pushBody = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "reminders", Map.of(
                                "created", List.of(Map.of(
                                        "id", id.toString(),
                                        "version", 0,
                                        "displayTitle", "Every 3 days PG",
                                        "type", "medication",
                                        "recurrenceRule", Map.of(
                                                "freq", "every_n_days",
                                                "interval", 3,
                                                "timesOfDay", List.of("09:00"),
                                                "until", "2026-12-31"),
                                        "startAt", "2026-07-01T09:00",
                                        "active", true,
                                        "clientId", UUID.randomUUID().toString()
                                )),
                                "updated", List.of(),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pushBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.rejected").isEmpty());

        MvcResult pullResult = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode pullBody = objectMapper.readTree(pullResult.getResponse().getContentAsString());
        JsonNode rr = pullBody.at("/changes/reminders/updated/0/recurrenceRule");
        assertThat(rr.isObject()).isTrue();
        assertThat(rr.get("freq").asText()).isEqualTo("every_n_days");
        assertThat(rr.get("interval").asInt()).isEqualTo(3);
        assertThat(rr.get("timesOfDay").get(0).asText()).isEqualTo("09:00");
        assertThat(rr.get("until").asText()).isEqualTo("2026-12-31");
    }
}
