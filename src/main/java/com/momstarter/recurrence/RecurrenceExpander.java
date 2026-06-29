package com.momstarter.recurrence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * FLAG-4: deterministic recurrence expansion. Produces minute-precision floating
 * civil strings "YYYY-MM-DDTHH:mm", byte-identical on every platform, so a
 * projected (wide-window) instance and a materialized (narrow-window) instance
 * hash to the same OccurrenceId. Civil-only: never consults a time zone.
 *
 * <h3>Golden test-vectors (data-model §3.5 / api-contract FLAG-4 §d)</h3>
 * <p>GV-1..GV-7 (9 cases) are the canonical cross-platform byte-equality contract.
 * Both {@code springboot-backend-dev} (Java) and {@code rn-mobile-dev} (JS) MUST pass
 * every vector identically — divergence forks the occurrence id and strands
 * legitimate {@code done}/{@code snoozed} rows permanently (adherence-data loss).
 */
public final class RecurrenceExpander {

    public enum Freq { ONE_OFF, DAILY, EVERY_N_DAYS }

    private RecurrenceExpander() {}

    /**
     * Expands a recurrence rule into a list of minute-precision civil datetime strings.
     *
     * @param freq        recurrence frequency
     * @param interval    step in days for {@code EVERY_N_DAYS}; ignored for other freqs
     * @param timesOfDay  canonical ascending "HH:mm" list (required for DAILY/EVERY_N_DAYS,
     *                    absent/empty for ONE_OFF)
     * @param startAt     the floating-civil anchor (date+time from {@code Reminder.startAt});
     *                    provides both the anchor date and the anchor time for the first-day guard
     * @param until       inclusive end of the recurrence (nullable)
     * @param windowStart inclusive expansion window start (civil date)
     * @param windowEnd   inclusive expansion window end (civil date)
     * @return ascending list of {@code "YYYY-MM-DDTHH:mm"} strings for occurrences
     *         in the window; never null
     */
    public static List<String> expand(Freq freq, int interval, List<String> timesOfDay,
                                      LocalDateTime startAt,
                                      LocalDate until,
                                      LocalDate windowStart, LocalDate windowEnd) {
        LocalDate startDate = startAt.toLocalDate();
        // Anchor time formatted as "HH:mm" (zero-padded) — the hash input for occurrence ids.
        String startTime = String.format("%02d:%02d", startAt.getHour(), startAt.getMinute());

        List<String> out = new ArrayList<>();
        LocalDate hardEnd = (until != null && until.isBefore(windowEnd)) ? until : windowEnd;

        if (freq == Freq.ONE_OFF) {
            // one_off fires exactly once at startAt; timesOfDay is absent per grammar.
            if (!startDate.isBefore(windowStart) && !startDate.isAfter(hardEnd)) {
                out.add(startDate + "T" + startTime);
            }
            return out;
        }

        int step = (freq == Freq.DAILY) ? 1 : Math.max(1, interval);

        // First on-grid occurrence on/after the window start.
        // gap = max(0, civilDaysBetween(anchor.date, windowStart))
        // k0  = ceil(gap / step)
        LocalDate first = startDate;
        if (first.isBefore(windowStart)) {
            long gap = ChronoUnit.DAYS.between(startDate, windowStart);
            long k0 = (gap + step - 1) / step; // ceil(gap / step)
            first = startDate.plusDays(k0 * step);
        }
        // If first > hardEnd (anchor date is after window end or k0 advances past end)
        // the loop body never executes → empty result (covers GV-5 / window-before-anchor).

        for (LocalDate d = first; !d.isAfter(hardEnd); d = d.plusDays(step)) {
            boolean isAnchorDate = d.equals(startDate);
            for (String t : timesOfDay) {
                // First-day anchor guard: on the anchor date, skip times earlier than startAt's time.
                // Comparison is lexicographic on "HH:mm" zero-padded strings, which is correct.
                if (isAnchorDate && t.compareTo(startTime) < 0) {
                    continue;
                }
                out.add(d + "T" + t); // "YYYY-MM-DD" + "T" + "HH:mm"
            }
        }
        // Output is already ascending: dates ascend in the loop; timesOfDay is
        // canonical-ascending (validated by the server); so no sort needed.
        return out;
    }
}
