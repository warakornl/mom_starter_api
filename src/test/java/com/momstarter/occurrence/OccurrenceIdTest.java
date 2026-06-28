package com.momstarter.occurrence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OccurrenceIdTest {

    // RFC 4122 known vector: validates the v5 algorithm itself.
    private static final UUID DNS_NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    @Test
    void v5_matches_rfc4122_known_vector() {
        UUID id = OccurrenceId.v5(DNS_NAMESPACE, "www.example.com");
        assertEquals("2ed6657d-e927-568b-95e1-2665a8aea6a2", id.toString());
        assertEquals(5, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void compute_is_deterministic() {
        UUID a = OccurrenceId.compute("rem-123", "2026-06-28T08:00");
        UUID b = OccurrenceId.compute("rem-123", "2026-06-28T08:00");
        assertEquals(a, b);
    }

    @Test
    void distinct_reminder_yields_distinct_id() {
        UUID a = OccurrenceId.compute("rem-123", "2026-06-28T08:00");
        UUID b = OccurrenceId.compute("rem-999", "2026-06-28T08:00");
        assertNotEquals(a, b);
    }

    @Test
    void distinct_civil_time_yields_distinct_id() {
        UUID a = OccurrenceId.compute("rem-123", "2026-06-28T08:00");
        UUID b = OccurrenceId.compute("rem-123", "2026-06-28T08:01");
        assertNotEquals(a, b);
    }
}
