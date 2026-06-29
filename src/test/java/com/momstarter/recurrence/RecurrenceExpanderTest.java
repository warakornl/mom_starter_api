package com.momstarter.recurrence;

import com.momstarter.occurrence.OccurrenceId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RecurrenceExpander}.
 *
 * <h3>Golden test-vectors (GV-1..GV-7, 9 cases — data-model §3.5, FLAG-4 §d)</h3>
 * <p>These are the canonical cross-platform byte-equality contract.  Any drift in the
 * expansion output {@code scheduledLocalCivil} string diverges the uuidv5 occurrence id
 * and permanently strands {@code done}/{@code snoozed} rows (adherence-data loss).
 * Both this Java implementation and the JS mobile implementation MUST produce identical
 * output for every row below.
 */
class RecurrenceExpanderTest {

    // =========================================================================
    // Existing baseline tests (signature updated: startAt is now LocalDateTime)
    // =========================================================================

    @Test
    void daily_expansion_inclusive_of_window() {
        // startAt = 2026-06-01T08:00 — anchor guard: "08:00" is NOT < "08:00" → emits on day 1
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("08:00"),
                LocalDateTime.of(2026, 6, 1, 8, 0),
                null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-03"));
        assertEquals(List.of("2026-06-01T08:00", "2026-06-02T08:00", "2026-06-03T08:00"), out);
    }

    @Test
    void every_n_days_steps_on_grid() {
        // startAt = 2026-06-01T09:30 — windowStart is anchor date, "09:30" not < "09:30"
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.EVERY_N_DAYS, 2, List.of("09:30"),
                LocalDateTime.of(2026, 6, 1, 9, 30),
                null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-05"));
        assertEquals(List.of("2026-06-01T09:30", "2026-06-03T09:30", "2026-06-05T09:30"), out);
    }

    @Test
    void one_off_only_when_in_window() {
        // one_off: time comes from startAt, not timesOfDay
        List<String> inWindow = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.ONE_OFF, 0, List.of(),
                LocalDateTime.of(2026, 6, 10, 7, 0),
                null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        assertEquals(List.of("2026-06-10T07:00"), inWindow);

        List<String> outOfWindow = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.ONE_OFF, 0, List.of(),
                LocalDateTime.of(2026, 7, 10, 7, 0),
                null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        assertEquals(List.of(), outOfWindow);
    }

    @Test
    void respects_inclusive_until() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("08:00"),
                LocalDateTime.of(2026, 6, 1, 8, 0),
                LocalDate.parse("2026-06-02"),
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        assertEquals(List.of("2026-06-01T08:00", "2026-06-02T08:00"), out);
    }

    @Test
    void projection_and_materialization_yield_the_same_occurrence_id() {
        String reminderId = "rem-abc";
        // Wide projection window vs narrow materialization window.
        List<String> projected = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("08:00"),
                LocalDateTime.of(2026, 6, 1, 8, 0),
                null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        List<String> materialized = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("08:00"),
                LocalDateTime.of(2026, 6, 1, 8, 0),
                null,
                LocalDate.parse("2026-06-15"), LocalDate.parse("2026-06-15"));

        String civil = "2026-06-15T08:00";
        // both windows contain the same civil instant...
        assertTrue(projected.contains(civil));
        assertEquals(List.of(civil), materialized);
        // ...and it hashes to one occurrence id regardless of which path produced it.
        assertEquals(OccurrenceId.compute(reminderId, civil),
                OccurrenceId.compute(reminderId, materialized.get(0)));
    }

    // =========================================================================
    // Golden test-vectors GV-1..GV-7 (9 cases) — CANONICAL byte-equality fixture
    // data-model §3.5 / api-contract FLAG-4 §d
    // =========================================================================

    /**
     * GV-1: {@code daily} fully inside window — 3 days, 1 time each.
     */
    @Test
    void gv1_daily_fully_in_window() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("08:00"),
                LocalDateTime.of(2026, 7, 1, 8, 0),
                null,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3));
        assertEquals(List.of(
                "2026-07-01T08:00",
                "2026-07-02T08:00",
                "2026-07-03T08:00"), out);
    }

    /**
     * GV-2: {@code every_n_days} interval=3, windowStart OFF-CYCLE.
     * gap=4, step=3 → k0=ceil(4/3)=2 → first d = 2026-07-01 + 6 = 2026-07-07.
     */
    @Test
    void gv2_every_n_days_off_cycle_k0_ceil() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.EVERY_N_DAYS, 3, List.of("09:00"),
                LocalDateTime.of(2026, 7, 1, 9, 0),
                null,
                LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 14));
        assertEquals(List.of(
                "2026-07-07T09:00",
                "2026-07-10T09:00",
                "2026-07-13T09:00"), out);
    }

    /**
     * GV-2b: {@code every_n_days} interval=3, windowStart exactly ON-CYCLE.
     * gap=3, step=3 → k0=ceil(3/3)=1 → first d = 2026-07-01 + 3 = 2026-07-04.
     * Window end 2026-07-10 is on-cycle and INCLUSIVE.
     */
    @Test
    void gv2b_every_n_days_on_cycle_inclusive_end() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.EVERY_N_DAYS, 3, List.of("09:00"),
                LocalDateTime.of(2026, 7, 1, 9, 0),
                null,
                LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 10));
        assertEquals(List.of(
                "2026-07-04T09:00",
                "2026-07-07T09:00",
                "2026-07-10T09:00"), out);
    }

    /**
     * GV-3: First-day anchor guard + multi-{@code timesOfDay} stable ascending ordering.
     * startAt = 2026-07-01T14:00; on the anchor date 2026-07-01, "08:00" < "14:00" → skipped.
     * All three times emit on 2026-07-02 (non-anchor date).
     */
    @Test
    void gv3_anchor_guard_and_multi_times_of_day() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("08:00", "14:00", "20:00"),
                LocalDateTime.of(2026, 7, 1, 14, 0),
                null,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));
        assertEquals(List.of(
                "2026-07-01T14:00",
                "2026-07-01T20:00",
                "2026-07-02T08:00",
                "2026-07-02T14:00",
                "2026-07-02T20:00"), out);
    }

    /**
     * GV-4: {@code until} is INCLUSIVE — window extends past {@code until}; stops at 2026-07-03.
     */
    @Test
    void gv4_until_is_inclusive() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("07:00"),
                LocalDateTime.of(2026, 7, 1, 7, 0),
                LocalDate.of(2026, 7, 3),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertEquals(List.of(
                "2026-07-01T07:00",
                "2026-07-02T07:00",
                "2026-07-03T07:00"), out);
    }

    /**
     * GV-5: Window entirely BEFORE anchor → empty.
     * startAt = 2026-07-10T08:00, window [2026-07-01, 2026-07-05].
     * gap = max(0, −9) = 0; k0 = 0; first d = 2026-07-10 > hardEnd=2026-07-05 → no iterations.
     */
    @Test
    void gv5_window_before_anchor_yields_empty() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("08:00"),
                LocalDateTime.of(2026, 7, 10, 8, 0),
                null,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5));
        assertEquals(List.of(), out);
    }

    /**
     * GV-6: {@code one_off} anchor date IN window → single occurrence at anchor time.
     */
    @Test
    void gv6_one_off_in_window() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.ONE_OFF, 0, List.of(),
                LocalDateTime.of(2026, 7, 15, 10, 30),
                null,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertEquals(List.of("2026-07-15T10:30"), out);
    }

    /**
     * GV-6b: {@code one_off} anchor date OUTSIDE window → empty.
     * Anchor 2026-07-15, window [2026-07-16, 2026-07-31].
     */
    @Test
    void gv6b_one_off_outside_window_yields_empty() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.ONE_OFF, 0, List.of(),
                LocalDateTime.of(2026, 7, 15, 10, 30),
                null,
                LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 31));
        assertEquals(List.of(), out);
    }

    /**
     * GV-7: Zero-pad format proof — single-digit month/day/hour/minute all 2-padded;
     * {@code T} literal, no seconds/zone. 2026-01-05T09:05 in [2026-01-01, 2026-01-31].
     */
    @Test
    void gv7_zero_pad_format() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.ONE_OFF, 0, List.of(),
                LocalDateTime.of(2026, 1, 5, 9, 5),
                null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertEquals(List.of("2026-01-05T09:05"), out);
    }
}
