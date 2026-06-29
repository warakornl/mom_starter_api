package com.momstarter.pregnancy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden test-vectors for the canonical postpartum-age computation (data-model §3.1 /
 * api-contract "Birth-event & postpartum counting — PINNED"). All 9 rows from the golden
 * table MUST pass byte-identically with the mobile implementation.
 *
 * <p>postpartumDays = max(0, civilDaysBetween(birthDate, today)); computed via
 * {@link PostpartumAge#compute(LocalDate, LocalDate)}.
 *
 * <p>Invariant checked on every row: {@code postpartumWeek*7 + postpartumDay == postpartumDays}.
 */
class PostpartumAgeTest {

    /** Fixed birth date anchor. Delta from today drives the calculation. */
    private static final LocalDate BIRTH = LocalDate.of(2026, 6, 29);

    // -------------------------------------------------------------------------
    // Golden test-vectors — 9 rows (canonical home: data-model §3.1)
    // -------------------------------------------------------------------------

    /**
     * Parameterized over all 9 rows of the postpartum golden table:
     * postpartumDays, expWeek, expDay
     */
    @ParameterizedTest(name = "postpartumDays={0} → week={1} day={2}")
    @CsvSource({
            // postpartumDays, expWeek, expDay
            "0,  0, 0",   // birth day — "สัปดาห์ที่ 0"
            "1,  0, 1",
            "6,  0, 6",
            "7,  1, 0",   // week boundary
            "13, 1, 6",
            "14, 2, 0",
            "41, 5, 6",
            "42, 6, 0",
            "84, 12, 0",  // ≈ end of typical postpartum window
    })
    void goldenVectors(int days, int expWeek, int expDay) {
        LocalDate today = BIRTH.plusDays(days);
        PostpartumAge pa = PostpartumAge.compute(BIRTH, today);

        assertThat(pa.postpartumDays())
                .as("postpartumDays for days=%d", days)
                .isEqualTo(days);

        assertThat(pa.postpartumWeek())
                .as("postpartumWeek for days=%d", days)
                .isEqualTo(expWeek);

        assertThat(pa.postpartumDay())
                .as("postpartumDay for days=%d", days)
                .isEqualTo(expDay);

        // Invariant: week*7 + day == days (must hold — floorDiv/floorMod mandated even though
        // postpartumDays >= 0, for parity with the gestational counter)
        assertThat(pa.postpartumWeek() * 7 + pa.postpartumDay())
                .as("invariant week*7+day==days for days=%d", days)
                .isEqualTo(pa.postpartumDays());
    }
}
