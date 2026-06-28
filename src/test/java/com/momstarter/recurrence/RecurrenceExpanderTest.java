package com.momstarter.recurrence;

import com.momstarter.occurrence.OccurrenceId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecurrenceExpanderTest {

    @Test
    void daily_expansion_inclusive_of_window() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("08:00"),
                LocalDate.parse("2026-06-01"), null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-03"));
        assertEquals(List.of("2026-06-01T08:00", "2026-06-02T08:00", "2026-06-03T08:00"), out);
    }

    @Test
    void every_n_days_steps_on_grid() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.EVERY_N_DAYS, 2, List.of("09:30"),
                LocalDate.parse("2026-06-01"), null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-05"));
        assertEquals(List.of("2026-06-01T09:30", "2026-06-03T09:30", "2026-06-05T09:30"), out);
    }

    @Test
    void one_off_only_when_in_window() {
        List<String> in = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.ONE_OFF, 0, List.of("07:00"),
                LocalDate.parse("2026-06-10"), null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        assertEquals(List.of("2026-06-10T07:00"), in);

        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.ONE_OFF, 0, List.of("07:00"),
                LocalDate.parse("2026-07-10"), null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        assertEquals(List.of(), out);
    }

    @Test
    void respects_inclusive_until() {
        List<String> out = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("08:00"),
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-02"),
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        assertEquals(List.of("2026-06-01T08:00", "2026-06-02T08:00"), out);
    }

    @Test
    void projection_and_materialization_yield_the_same_occurrence_id() {
        String reminderId = "rem-abc";
        // Wide projection window vs narrow materialization window.
        List<String> projected = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("08:00"),
                LocalDate.parse("2026-06-01"), null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        List<String> materialized = RecurrenceExpander.expand(
                RecurrenceExpander.Freq.DAILY, 1, List.of("08:00"),
                LocalDate.parse("2026-06-01"), null,
                LocalDate.parse("2026-06-15"), LocalDate.parse("2026-06-15"));

        String civil = "2026-06-15T08:00";
        // both windows contain the same civil instant...
        org.junit.jupiter.api.Assertions.assertTrue(projected.contains(civil));
        assertEquals(List.of(civil), materialized);
        // ...and it hashes to one occurrence id regardless of which path produced it.
        assertEquals(OccurrenceId.compute(reminderId, civil),
                OccurrenceId.compute(reminderId, materialized.get(0)));
    }
}
