package com.momstarter.occurrence;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusMergeTest {

    private static final Instant T1 = Instant.parse("2026-06-28T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-28T11:00:00Z"); // later

    @Test
    void handled_done_never_reverts_to_missed_even_if_missed_is_newer() {
        // device locally derived 'missed' (T2) arrives after a 'done' (T1) — done must win
        assertEquals("done", StatusMerge.merge("done", T1, "missed", T2));
    }

    @Test
    void handled_snoozed_never_reverts_to_missed() {
        assertEquals("snoozed", StatusMerge.merge("snoozed", T1, "missed", T2));
    }

    @Test
    void missed_is_upgraded_when_a_handled_action_arrives() {
        assertEquals("done", StatusMerge.merge("missed", T1, "done", T2));
    }

    @Test
    void non_missed_cases_fall_through_to_last_write_wins() {
        // due -> snoozed, snoozed is newer -> snoozed wins by LWW
        assertEquals("snoozed", StatusMerge.merge("due", T1, "snoozed", T2));
        // newer 'due' over older 'due' (idempotent) still resolves deterministically
        assertEquals("due", StatusMerge.merge("due", T2, "due", T1));
    }
}
