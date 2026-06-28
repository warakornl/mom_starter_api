package com.momstarter.calendar;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * FLAG-1: event-log timestamps are floating-civil. The calendar bucket is the
 * date part of the civil datetime, taken WITHOUT any time-zone conversion, so a
 * day's items never shift when the device crosses time zones / DST.
 */
public final class CivilDayBucketer {

    private CivilDayBucketer() {}

    /** Bucket a floating-civil datetime to its calendar day. Never tz-converted. */
    public static LocalDate bucket(LocalDateTime civil) {
        return civil.toLocalDate();
    }

    /** Convenience: bucket the minute-precision civil string "YYYY-MM-DDTHH:mm". */
    public static LocalDate bucket(String scheduledLocalCivil) {
        return LocalDate.parse(scheduledLocalCivil.substring(0, 10));
    }
}
