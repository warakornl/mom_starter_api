package com.momstarter.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.reminder.Reminder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link ReminderSyncCollection#toRecord(Reminder)}.
 *
 * <p>These tests do NOT start a Spring context or use H2. They directly instantiate
 * {@link ReminderSyncCollection} with a real {@link ObjectMapper} to exercise the
 * two JSON-deserialization branches in {@code toRecord()}:
 *
 * <ol>
 *   <li><strong>ObjectNode branch (production/PG path)</strong> — the stored {@code recurrenceRule}
 *       is a raw JSON object string like {@code {"freq":"daily","timesOfDay":["08:00"]}},
 *       exactly as PostgreSQL returns it from a {@code jsonb} column.
 *       {@code readTree()} yields an {@code ObjectNode}; {@code isTextual()} is {@code false};
 *       the result is placed in the record as-is.</li>
 *   <li><strong>TextNode branch (H2 double-encode path)</strong> — H2 in PostgreSQL MODE can
 *       return the stored value as a JSON-encoded string literal
 *       (e.g. {@code "{\"freq\":\"daily\",...}"}) rather than the raw object text.
 *       {@code readTree()} yields a {@code TextNode}; {@code isTextual()} is {@code true};
 *       {@code toRecord()} unwraps the outer quotes and re-parses to recover the
 *       {@code ObjectNode}.</li>
 *   <li><strong>null branch</strong> — a {@code null} {@code recurrenceRule} produces a
 *       {@code null} map entry without throwing.</li>
 * </ol>
 *
 * <p>ISSUE-2: these tests prove the ObjectNode branch is exercised and correct independently
 * of H2's lenient type coercion — they would have caught the BLOCKER-1 gap earlier.
 */
class ReminderToRecordUnitTest {

    /**
     * Real ObjectMapper (same behaviour as the Spring-managed bean).
     * {@code ReminderSyncCollection} takes {@code null} for the repository because
     * {@code toRecord()} never calls the repository.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReminderSyncCollection collection =
            new ReminderSyncCollection(null, objectMapper);

    // -------------------------------------------------------------------------
    // ObjectNode branch — raw PG-style JSON string
    // -------------------------------------------------------------------------

    /**
     * Simulates what PostgreSQL returns from a {@code jsonb} column:
     * the raw JSON object text (no outer double-quotes).
     * <p>{@code toRecord()} must deserialise this to an {@code ObjectNode} and place it
     * in the record, so the API wire value is a nested object, not a string.
     */
    @Test
    void toRecord_rawPgJsonString_recurrenceRuleIsObject() {
        Reminder r = buildReminder("{\"freq\":\"daily\",\"timesOfDay\":[\"08:00\"]}");

        Map<String, Object> record = collection.toRecord(r);

        Object rr = record.get("recurrenceRule");
        assertThat(rr).isInstanceOf(JsonNode.class);
        JsonNode node = (JsonNode) rr;
        assertThat(node.isObject())
                .as("Expected ObjectNode for raw PG JSON, got %s", node.getNodeType())
                .isTrue();
        assertThat(node.get("freq").asText()).isEqualTo("daily");
        assertThat(node.get("timesOfDay").get(0).asText()).isEqualTo("08:00");
    }

    /**
     * Raw PG-style JSON with {@code every_n_days}, {@code interval}, and {@code until}.
     * Proves the ObjectNode branch handles all FLAG-4 grammar fields correctly.
     */
    @Test
    void toRecord_rawPgJson_everyNDaysWithUntil_allFieldsPresent() {
        String json = "{\"freq\":\"every_n_days\",\"interval\":3,"
                + "\"timesOfDay\":[\"08:00\",\"20:00\"],\"until\":\"2026-12-31\"}";
        Reminder r = buildReminder(json);

        Map<String, Object> record = collection.toRecord(r);

        JsonNode node = (JsonNode) record.get("recurrenceRule");
        assertThat(node.isObject()).isTrue();
        assertThat(node.get("freq").asText()).isEqualTo("every_n_days");
        assertThat(node.get("interval").asInt()).isEqualTo(3);
        assertThat(node.get("timesOfDay").get(0).asText()).isEqualTo("08:00");
        assertThat(node.get("timesOfDay").get(1).asText()).isEqualTo("20:00");
        assertThat(node.get("until").asText()).isEqualTo("2026-12-31");
    }

    /**
     * Raw PG-style JSON for {@code one_off}.
     * Proves the ObjectNode branch handles the minimal grammar (freq only).
     */
    @Test
    void toRecord_rawPgJson_oneOff_objectNodeWithFreqOnly() {
        Reminder r = buildReminder("{\"freq\":\"one_off\"}");

        Map<String, Object> record = collection.toRecord(r);

        JsonNode node = (JsonNode) record.get("recurrenceRule");
        assertThat(node.isObject()).isTrue();
        assertThat(node.get("freq").asText()).isEqualTo("one_off");
        assertThat(node.has("timesOfDay")).isFalse();
        assertThat(node.has("interval")).isFalse();
    }

    // -------------------------------------------------------------------------
    // TextNode branch — H2 double-encoded value
    // -------------------------------------------------------------------------

    /**
     * Simulates what H2 in PostgreSQL MODE can return: the JSON object is wrapped in an
     * outer JSON string literal ({@code "\"{ ... }\""}).
     * <p>{@code toRecord()} must detect the {@code TextNode} result, unwrap one layer, and
     * re-parse to produce the correct {@code ObjectNode}.
     */
    @Test
    void toRecord_h2DoubleEncodedString_recurrenceRuleIsUnwrappedObject() {
        // Outer quotes + escaped inner content — what H2 returns for a jsonb column
        String h2Value = "\"{\\\"freq\\\":\\\"daily\\\",\\\"timesOfDay\\\":[\\\"08:00\\\"]}\"";
        Reminder r = buildReminder(h2Value);

        Map<String, Object> record = collection.toRecord(r);

        Object rr = record.get("recurrenceRule");
        assertThat(rr).isInstanceOf(JsonNode.class);
        JsonNode node = (JsonNode) rr;
        assertThat(node.isObject()).isTrue();
        assertThat(node.get("freq").asText()).isEqualTo("daily");
        assertThat(node.get("timesOfDay").get(0).asText()).isEqualTo("08:00");
    }

    // -------------------------------------------------------------------------
    // null branch
    // -------------------------------------------------------------------------

    /**
     * A {@code null} {@code recurrenceRule} must produce a {@code null} map entry
     * without throwing, preserving the tombstone-skeleton path in {@code applyDelete}.
     */
    @Test
    void toRecord_nullRecurrenceRule_producesNullInRecord() {
        Reminder r = buildReminder(null);

        Map<String, Object> record = collection.toRecord(r);

        assertThat(record).containsKey("recurrenceRule");
        assertThat(record.get("recurrenceRule")).isNull();
    }

    // -------------------------------------------------------------------------
    // Scalar field coverage
    // -------------------------------------------------------------------------

    /**
     * Verifies that scalar fields are copied correctly alongside {@code recurrenceRule}.
     */
    @Test
    void toRecord_scalarFields_copiedCorrectly() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Reminder r = new Reminder();
        r.setId(id);
        r.setUserId(userId);
        r.setType("medication");
        r.setDisplayTitle("Morning pills");
        r.setActive(false);
        r.setRecurrenceRule("{\"freq\":\"daily\",\"timesOfDay\":[\"07:00\"]}");
        r.setStartAt(LocalDateTime.of(2026, 7, 1, 7, 0));

        Map<String, Object> record = collection.toRecord(r);

        assertThat(record.get("id")).isEqualTo(id);
        assertThat(record.get("userId")).isEqualTo(userId);
        assertThat(record.get("type")).isEqualTo("medication");
        assertThat(record.get("displayTitle")).isEqualTo("Morning pills");
        assertThat(record.get("active")).isEqualTo(false);
        assertThat(record.get("startAt")).isEqualTo("2026-07-01T07:00");
        assertThat(record.get("version")).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static Reminder buildReminder(String recurrenceRuleJson) {
        Reminder r = new Reminder();
        r.setId(UUID.randomUUID());
        r.setUserId(UUID.randomUUID());
        r.setType("custom");
        r.setDisplayTitle("Unit test reminder");
        r.setRecurrenceRule(recurrenceRuleJson);
        r.setStartAt(LocalDateTime.of(2026, 7, 1, 8, 0));
        r.setActive(true);
        return r;
    }
}
