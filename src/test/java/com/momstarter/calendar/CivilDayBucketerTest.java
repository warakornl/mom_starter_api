package com.momstarter.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CivilDayBucketerTest {

    @Test
    void bucket_is_the_civil_date_part() {
        LocalDateTime feedLoggedLateNight = LocalDateTime.parse("2026-06-28T23:30");
        assertEquals(LocalDate.parse("2026-06-28"), CivilDayBucketer.bucket(feedLoggedLateNight));
    }

    @Test
    void bucket_is_tz_stable_for_a_floating_civil_value() {
        // The same floating-civil value always buckets to the same day, with no
        // device time zone in play — a 23:30 feed never rolls into the next day.
        String civil = "2026-06-28T23:30";
        assertEquals(LocalDate.parse("2026-06-28"), CivilDayBucketer.bucket(civil));
        assertEquals(CivilDayBucketer.bucket(civil),
                CivilDayBucketer.bucket(LocalDateTime.parse(civil)));
    }

    @Test
    void string_and_datetime_overloads_agree() {
        assertEquals(CivilDayBucketer.bucket("2026-01-01T00:00"),
                CivilDayBucketer.bucket(LocalDateTime.parse("2026-01-01T00:00")));
    }
}
