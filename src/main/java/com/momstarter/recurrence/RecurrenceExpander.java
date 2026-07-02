package com.momstarter.recurrence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FLAG-4: deterministic recurrence expansion. Produces minute-precision floating
 * civil strings "YYYY-MM-DDTHH:mm", byte-identical on every platform, so a
 * projected (wide-window) instance and a materialized (narrow-window) instance
 * hash to the same OccurrenceId. Civil-only: never consults a time zone.
 *
 * <h3>Golden test-vectors (data-model §3.5 / api-contract FLAG-4 §d)</h3>
 * <p>GV-1..GV-7 (9 cases) are the canonical cross-platform byte-equality contract for
 * {@code ONE_OFF}/{@code DAILY}/{@code EVERY_N_DAYS}.
 * GV-8..GV-13 extend the contract to the {@code WEEKLY}/{@code byDay} branch.
 * Both {@code springboot-backend-dev} (Java) and {@code rn-mobile-dev} (JS) MUST pass
 * every vector identically — divergence forks the occurrence id and strands
 * legitimate {@code done}/{@code snoozed} rows permanently (adherence-data loss).
 */
public final class RecurrenceExpander {

    public enum Freq { ONE_OFF, DAILY, EVERY_N_DAYS, WEEKLY }

    /**
     * Structured expansion parameters.  Introduced alongside the {@code WEEKLY}/{@code byDay}
     * extension (data-model §3.5 GV-8..GV-13, recurrence-weekly-byday-design §5 item 5) to
     * prevent positional-argument mis-ordering when a {@code byDay} list was bolted onto the
     * existing 7-positional-argument {@code expand()} signature.
     * The TS expander takes a {@code rule} object so adding {@code byDay} there is a
     * non-breaking field addition; the Java side uses this record for the same purpose.
     *
     * @param freq        recurrence frequency
     * @param interval    step in days for {@code EVERY_N_DAYS}; weeks for {@code WEEKLY}
     *                    (absent/0 → treated as 1); ignored for other freqs
     * @param timesOfDay  canonical ascending "HH:mm" list; required for
     *                    DAILY/EVERY_N_DAYS/WEEKLY, absent/empty for ONE_OFF
     * @param byDay       canonical ascending ISO weekday tokens e.g. {@code ["MO","WE","FR"]};
     *                    required/non-empty for WEEKLY; null or empty for all other freqs
     * @param startAt     floating-civil anchor (date+time from {@code Reminder.startAt})
     * @param until       inclusive civil end date (nullable = no end)
     * @param windowStart inclusive expansion window start
     * @param windowEnd   inclusive expansion window end
     */
    public record ExpandParams(
            Freq freq,
            int interval,
            List<String> timesOfDay,
            List<String> byDay,
            LocalDateTime startAt,
            LocalDate until,
            LocalDate windowStart,
            LocalDate windowEnd) {}

    // ISO weekday tokens indexed 0=MO .. 6=SU — used by the weekly branch.
    // Pinned formula (design §2.1): isoDow0(epochDay) = ((epochDay+3) mod 7 +7) mod 7
    private static final String[] DOW_TOKENS = {"MO", "TU", "WE", "TH", "FR", "SA", "SU"};

    private RecurrenceExpander() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Full-params overload — preferred for new callers, required for {@code WEEKLY}.
     *
     * @param p expansion parameters (see {@link ExpandParams})
     * @return ascending list of {@code "YYYY-MM-DDTHH:mm"} strings; never null
     */
    public static List<String> expand(ExpandParams p) {
        LocalDate startDate = p.startAt().toLocalDate();
        String startTime = String.format("%02d:%02d",
                p.startAt().getHour(), p.startAt().getMinute());
        List<String> out = new ArrayList<>();
        LocalDate hardEnd = (p.until() != null && p.until().isBefore(p.windowEnd()))
                ? p.until() : p.windowEnd();

        if (p.freq() == Freq.ONE_OFF) {
            if (!startDate.isBefore(p.windowStart()) && !startDate.isAfter(hardEnd)) {
                out.add(startDate + "T" + startTime);
            }
            return out;
        }

        if (p.freq() == Freq.WEEKLY) {
            return expandWeekly(p, startDate, startTime, hardEnd);
        }

        // DAILY / EVERY_N_DAYS — existing day-step logic (unchanged).
        int step = (p.freq() == Freq.DAILY) ? 1 : Math.max(1, p.interval());
        LocalDate first = startDate;
        if (first.isBefore(p.windowStart())) {
            long gap = ChronoUnit.DAYS.between(startDate, p.windowStart());
            long k0 = (gap + step - 1) / step;
            first = startDate.plusDays(k0 * step);
        }
        for (LocalDate d = first; !d.isAfter(hardEnd); d = d.plusDays(step)) {
            boolean isAnchorDate = d.equals(startDate);
            for (String t : p.timesOfDay()) {
                if (isAnchorDate && t.compareTo(startTime) < 0) continue;
                out.add(d + "T" + t);
            }
        }
        return out;
    }

    /**
     * Backward-compatible 7-argument overload for {@code ONE_OFF}/{@code DAILY}/{@code EVERY_N_DAYS}.
     * {@code byDay} is implicitly empty. All existing callers remain unchanged.
     *
     * @param freq        recurrence frequency
     * @param interval    step in days for {@code EVERY_N_DAYS}; ignored otherwise
     * @param timesOfDay  canonical ascending "HH:mm" list
     * @param startAt     floating-civil anchor
     * @param until       inclusive end (nullable)
     * @param windowStart inclusive window start
     * @param windowEnd   inclusive window end
     * @return ascending list of {@code "YYYY-MM-DDTHH:mm"} strings; never null
     */
    public static List<String> expand(Freq freq, int interval, List<String> timesOfDay,
                                      LocalDateTime startAt,
                                      LocalDate until,
                                      LocalDate windowStart, LocalDate windowEnd) {
        return expand(new ExpandParams(freq, interval, timesOfDay, List.of(),
                startAt, until, windowStart, windowEnd));
    }

    // -------------------------------------------------------------------------
    // WEEKLY branch — civil-integer formulas pinned in design §2.1–2.2
    // -------------------------------------------------------------------------

    /**
     * Expands a {@code WEEKLY} rule using the day-walk + weekIndex filter algorithm
     * from design §2.2.  All arithmetic uses epoch-day integers to guarantee
     * byte-identical results with the TS mobile expander.
     *
     * <p>Formulas (design §2.1):
     * <pre>
     *   isoDow0(epochDay) = ((epochDay + 3) mod 7 + 7) mod 7   // 0=MO, 1=TU, ..., 6=SU
     *   tokenOf(epochDay) = DOW_TOKENS[isoDow0(epochDay)]
     *   mondayOf(epochDay) = epochDay - isoDow0(epochDay)
     * </pre>
     * Verification: 1970-01-01 is epoch day 0 (Thursday); (0+3) mod 7 = 3 = TH ✓
     */
    private static List<String> expandWeekly(ExpandParams p, LocalDate startDate,
                                              String startTime, LocalDate hardEnd) {
        List<String> out = new ArrayList<>();
        List<String> byDay = (p.byDay() != null) ? p.byDay() : List.of();
        if (byDay.isEmpty()) return out; // guard: byDay required for weekly

        Set<String> byDaySet = new HashSet<>(byDay);
        int weekInterval = Math.max(1, p.interval());

        long anchorEpochDay = startDate.toEpochDay();
        long anchorMonday = mondayOf(anchorEpochDay);

        // start = max(anchor.date, windowStart) — never back-fill before the anchor
        LocalDate start = startDate.isBefore(p.windowStart()) ? p.windowStart() : startDate;

        // Day-walk: iterate every civil day in [start, hardEnd] and filter
        for (LocalDate d = start; !d.isAfter(hardEnd); d = d.plusDays(1)) {
            long epochDay = d.toEpochDay();
            // weekIndex = number of ISO weeks elapsed since the anchor's Monday-week
            long weekIndex = (mondayOf(epochDay) - anchorMonday) / 7;
            if (weekIndex % weekInterval != 0) continue;      // wrong week in cycle
            if (!byDaySet.contains(tokenOf(epochDay))) continue; // wrong weekday

            boolean isAnchorDate = d.equals(startDate);
            for (String t : p.timesOfDay()) {
                // First-day anchor guard (same as DAILY/EVERY_N_DAYS)
                if (isAnchorDate && t.compareTo(startTime) < 0) continue;
                out.add(d + "T" + t);
            }
        }
        // Output is already ascending: dates ascend in day-walk; timesOfDay canonical-ascending
        return out;
    }

    // -------------------------------------------------------------------------
    // Epoch-day weekday helpers — §2.1 pinned civil-integer formulas
    // -------------------------------------------------------------------------

    /** Returns ISO day-of-week index: 0=MO, 1=TU, 2=WE, 3=TH, 4=FR, 5=SA, 6=SU. */
    static int isoDow0(long epochDay) {
        return (int) (((epochDay + 3) % 7 + 7) % 7);
    }

    /** Returns the ISO weekday token (e.g. "WE") for the given epoch day. */
    static String tokenOf(long epochDay) {
        return DOW_TOKENS[isoDow0(epochDay)];
    }

    /** Returns the epoch day of the ISO Monday that starts the week containing {@code epochDay}. */
    static long mondayOf(long epochDay) {
        return epochDay - isoDow0(epochDay);
    }
}
