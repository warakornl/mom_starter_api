package com.momstarter.recurrence;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * FLAG-4: deterministic recurrence expansion. Produces minute-precision floating
 * civil strings "YYYY-MM-DDTHH:mm", byte-identical on every platform, so a
 * projected (wide-window) instance and a materialized (narrow-window) instance
 * hash to the same OccurrenceId. Civil-only: never consults a time zone.
 */
public final class RecurrenceExpander {

    public enum Freq { ONE_OFF, DAILY, EVERY_N_DAYS }

    private RecurrenceExpander() {}

    /**
     * @param timesOfDay minute-precision "HH:mm" strings
     * @param until      inclusive end of the recurrence (nullable)
     * @param windowStart inclusive expansion window start (civil date)
     * @param windowEnd   inclusive expansion window end (civil date)
     */
    public static List<String> expand(Freq freq, int interval, List<String> timesOfDay,
                                      LocalDate startDate, LocalDate until,
                                      LocalDate windowStart, LocalDate windowEnd) {
        List<String> out = new ArrayList<>();
        LocalDate hardEnd = (until != null && until.isBefore(windowEnd)) ? until : windowEnd;

        if (freq == Freq.ONE_OFF) {
            if (!startDate.isBefore(windowStart) && !startDate.isAfter(hardEnd)) {
                emit(out, startDate, timesOfDay);
            }
            return out;
        }

        int step = (freq == Freq.DAILY) ? 1 : Math.max(1, interval);

        // First on-grid occurrence on/after the window start (ceil of the gap in steps).
        LocalDate first = startDate;
        if (first.isBefore(windowStart)) {
            long gap = ChronoUnit.DAYS.between(startDate, windowStart);
            long stepsAhead = (gap + step - 1) / step; // ceil
            first = startDate.plusDays(stepsAhead * step);
        }

        for (LocalDate d = first; !d.isAfter(hardEnd); d = d.plusDays(step)) {
            emit(out, d, timesOfDay);
        }
        return out;
    }

    private static void emit(List<String> out, LocalDate d, List<String> timesOfDay) {
        for (String t : timesOfDay) {
            out.add(d.toString() + "T" + t); // "YYYY-MM-DD" + "T" + "HH:mm"
        }
    }
}
