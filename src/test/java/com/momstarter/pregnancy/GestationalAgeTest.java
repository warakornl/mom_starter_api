package com.momstarter.pregnancy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Golden test-vectors for the canonical gestational-age computation (data-model §3.1 / api-contract
 * "Gestational-age & stage computation — PINNED"). All 14 rows from the golden table must pass
 * byte-identically with the mobile implementation.
 *
 * <p>The invariant {@code gestationalWeek * 7 + gestationalDay == daysPregnant} MUST hold for
 * every row, including the negative band — this is the lockstep guard against server/client drift
 * on the floorDiv/floorMod rules.
 *
 * <p>EDD is derived from a fixed "today" anchor via:
 * {@code edd = today + daysUntilEdd}, where {@code daysUntilEdd = 280 - daysPregnant}.
 */
class GestationalAgeTest {

    /**
     * Fixed civil-date anchor. Chosen to be a round date; the arithmetic only depends on the delta
     * between today and edd, so the absolute date does not matter for the golden vectors.
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 29);

    private static LocalDate eddFor(int daysPregnant) {
        long daysUntilEdd = 280L - daysPregnant;
        return TODAY.plusDays(daysUntilEdd);
    }

    // -------------------------------------------------------------------------
    // Golden test-vectors — 14 rows (canonical home: data-model §3.1)
    // -------------------------------------------------------------------------

    /**
     * Parameterized over all 14 rows of the golden table:
     * daysPregnant, expWeek, expDay, expProgress, expStage
     *
     * <p>progress column uses the string representation of the clamp(d/280.0, 0, 1) value:
     *   negative d → 0.0 (clamp floor)
     *   d >= 280   → 1.0 (clamp ceiling)
     *   otherwise  → d/280.0
     */
    @ParameterizedTest(name = "daysPregnant={0} → week={1} day={2} progress≈{3} stage={4}")
    @CsvSource({
            // daysPregnant, expWeek, expDay, expProgress, expStage
            "-28,  -4, 0, 0.0,             T1",
            "-8,   -2, 6, 0.0,             T1",
            "-1,   -1, 6, 0.0,             T1",
            "0,     0, 0, 0.0,             T1",
            "1,     0, 1, 0.003571428571,  T1",
            "6,     0, 6, 0.021428571428,  T1",
            "7,     1, 0, 0.025,           T1",
            "91,   13, 0, 0.325,           T1",
            "188,  26, 6, 0.671428571428,  T2",
            "279,  39, 6, 0.996428571428,  T3",
            "280,  40, 0, 1.0,             T3",
            "287,  41, 0, 1.0,             T3",
            "294,  42, 0, 1.0,             T3",
            "308,  44, 0, 1.0,             T3",
    })
    void goldenVectors(int daysPregnant, int expWeek, int expDay, double expProgress, String expStage) {
        LocalDate edd = eddFor(daysPregnant);
        GestationalAge ga = GestationalAge.compute(edd, TODAY, "pregnant");

        assertThat(ga.gestationalWeek())
                .as("gestationalWeek for daysPregnant=%d", daysPregnant)
                .isEqualTo(expWeek);

        assertThat(ga.gestationalDay())
                .as("gestationalDay for daysPregnant=%d", daysPregnant)
                .isEqualTo(expDay);

        assertThat(ga.progress())
                .as("progress for daysPregnant=%d", daysPregnant)
                .isCloseTo(expProgress, within(1e-8));

        assertThat(ga.currentStage())
                .as("currentStage for daysPregnant=%d", daysPregnant)
                .isEqualTo(expStage);

        // Invariant: week*7 + day == daysPregnant (must hold for negatives with floorDiv/floorMod)
        assertThat((long) ga.gestationalWeek() * 7 + ga.gestationalDay())
                .as("invariant week*7+day==daysPregnant for daysPregnant=%d", daysPregnant)
                .isEqualTo(daysPregnant);
    }

    // -------------------------------------------------------------------------
    // daysRemaining (= daysUntilEdd)
    // -------------------------------------------------------------------------

    @Test
    void daysRemaining_equalsChronoDaysBetweenTodayAndEdd() {
        // daysPregnant=7 => daysUntilEdd=273 => daysRemaining=273
        LocalDate edd = eddFor(7);
        GestationalAge ga = GestationalAge.compute(edd, TODAY, "pregnant");
        assertThat(ga.daysRemaining()).isEqualTo(273L);
    }

    @Test
    void daysRemaining_isNegativePastEdd() {
        // daysPregnant=287 => daysUntilEdd=-7 => daysRemaining=-7
        LocalDate edd = eddFor(287);
        GestationalAge ga = GestationalAge.compute(edd, TODAY, "pregnant");
        assertThat(ga.daysRemaining()).isEqualTo(-7L);
    }

    // -------------------------------------------------------------------------
    // deliveryWindowActive
    // -------------------------------------------------------------------------

    @Test
    void deliveryWindowActive_falseBeforeWeek37() {
        // week=13 (T1)
        LocalDate edd = eddFor(91);
        GestationalAge ga = GestationalAge.compute(edd, TODAY, "pregnant");
        assertThat(ga.deliveryWindowActive()).isFalse();
    }

    @Test
    void deliveryWindowActive_trueAtWeek37_whenPregnant() {
        // daysPregnant=259 => week=37, day=0
        int daysPregnant = 259;
        LocalDate edd = eddFor(daysPregnant);
        GestationalAge ga = GestationalAge.compute(edd, TODAY, "pregnant");
        assertThat(ga.gestationalWeek()).isEqualTo(37);
        assertThat(ga.deliveryWindowActive()).isTrue();
    }

    @Test
    void deliveryWindowActive_trueForGoldenRows_279and280and287and294and308() {
        for (int d : new int[]{279, 280, 287, 294, 308}) {
            LocalDate edd = eddFor(d);
            GestationalAge ga = GestationalAge.compute(edd, TODAY, "pregnant");
            assertThat(ga.deliveryWindowActive())
                    .as("deliveryWindowActive for daysPregnant=%d (week=%d)", d, ga.gestationalWeek())
                    .isTrue();
        }
    }

    @Test
    void deliveryWindowActive_falseWhenNotPregnant() {
        // week=37 but lifecycle=postpartum → must be false
        LocalDate edd = eddFor(259);
        GestationalAge ga = GestationalAge.compute(edd, TODAY, "postpartum");
        assertThat(ga.deliveryWindowActive()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Stage boundary edges
    // -------------------------------------------------------------------------

    @Test
    void stageEdge_week13isT1_week14isT2() {
        // week=13 → daysPregnant=91
        assertThat(GestationalAge.compute(eddFor(91), TODAY, "pregnant").currentStage()).isEqualTo("T1");
        // week=14 → daysPregnant=98
        assertThat(GestationalAge.compute(eddFor(98), TODAY, "pregnant").currentStage()).isEqualTo("T2");
    }

    @Test
    void stageEdge_week27isT2_week28isT3() {
        // week=27 → daysPregnant=189
        assertThat(GestationalAge.compute(eddFor(189), TODAY, "pregnant").currentStage()).isEqualTo("T2");
        // week=28 → daysPregnant=196
        assertThat(GestationalAge.compute(eddFor(196), TODAY, "pregnant").currentStage()).isEqualTo("T3");
    }

    // -------------------------------------------------------------------------
    // displayedWeek
    // -------------------------------------------------------------------------

    @Test
    void displayedWeek_clampedToZeroForNegativeWeeks() {
        for (int d : new int[]{-28, -8, -1}) {
            LocalDate edd = eddFor(d);
            GestationalAge ga = GestationalAge.compute(edd, TODAY, "pregnant");
            assertThat(ga.displayedWeek())
                    .as("displayedWeek should be 0 for daysPregnant=%d (raw week=%d)", d, ga.gestationalWeek())
                    .isZero();
            assertThat(ga.gestationalWeek())
                    .as("raw gestationalWeek should be negative for daysPregnant=%d", d)
                    .isNegative();
        }
    }

    @Test
    void displayedWeek_notCappedAtUpperEnd() {
        // week=44 (daysPregnant=308) — upper end is NOT clamped, renders faithfully
        LocalDate edd = eddFor(308);
        GestationalAge ga = GestationalAge.compute(edd, TODAY, "pregnant");
        assertThat(ga.displayedWeek()).isEqualTo(44);
    }

    // -------------------------------------------------------------------------
    // progress — real (float) division guard
    // -------------------------------------------------------------------------

    @Test
    void progress_isRealDivision_notIntegerDivision() {
        // If integer division were used, daysPregnant=1..279 would all give 0.
        LocalDate edd = eddFor(1);
        GestationalAge ga = GestationalAge.compute(edd, TODAY, "pregnant");
        assertThat(ga.progress()).isGreaterThan(0.0);
    }

    @Test
    void progress_clampsToOneAtAndBeyondEdd() {
        for (int d : new int[]{280, 287, 294, 308}) {
            GestationalAge ga = GestationalAge.compute(eddFor(d), TODAY, "pregnant");
            assertThat(ga.progress())
                    .as("progress should be 1.0 for daysPregnant=%d", d)
                    .isEqualTo(1.0);
        }
    }
}
