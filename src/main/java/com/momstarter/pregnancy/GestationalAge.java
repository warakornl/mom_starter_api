package com.momstarter.pregnancy;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Canonical gestational-age &amp; stage computation — PINNED (data-model §3.1 /
 * api-contract "Gestational-age &amp; stage computation").
 *
 * <p><strong>Frozen shared algorithm.</strong> Both {@code springboot-backend-dev} (response snapshot)
 * and {@code rn-mobile-dev} (on-device daily counter) MUST implement this EXACT algorithm,
 * byte-identically — frozen the same way the {@code OCCURRENCE_NAMESPACE} UUIDv5 derivation is
 * frozen, so server and client can never disagree on the week. Canonical home: {@code data-model.md}
 * §3.1 "Canonical gestational-age &amp; stage computation"; this class is its backend implementation.
 *
 * <p><strong>MANDATORY: {@link Math#floorDiv} / {@link Math#floorMod}, never raw {@code /} or
 * {@code %}</strong>.  {@code daysPregnant} goes <em>negative</em> in the valid EDD window
 * ({@code edd ∈ [today+281, today+308]}). Java's {@code /} and {@code %} truncate toward 0 while
 * {@link Math#floorDiv}/{@link Math#floorMod} round toward −∞ — so a naïve port drifts between
 * backend and mobile (e.g. {@code daysPregnant = −1}: truncating gives week 0, floor gives week −1).
 *
 * <p><strong>Scope</strong>: the {@code pregnant} lifecycle only ({@code birthDatetime == null}).
 * Postpartum counting is deferred to the birth-event phase (OQ-8/10/11).
 *
 * @param daysPregnant       280 − daysUntilEdd; negative in the pre-pregnancy EDD-window band
 * @param gestationalWeek    floorDiv(daysPregnant, 7) — COMPLETED weeks; can be negative
 * @param gestationalDay     floorMod(daysPregnant, 7) — Euclidean remainder 0..6
 * @param daysRemaining      daysUntilEdd (negative once past EDD)
 * @param progress           clamp(daysPregnant / 280.0, 0, 1) — REAL division
 * @param currentStage       "T1" / "T2" / "T3"
 * @param deliveryWindowActive lifecycle==pregnant AND gestationalWeek≥37 — overlay, never a stage
 */
public record GestationalAge(
        long daysPregnant,
        int gestationalWeek,
        int gestationalDay,
        long daysRemaining,
        double progress,
        String currentStage,
        boolean deliveryWindowActive) {

    /**
     * Computes the gestational-age snapshot from the stored {@code edd} and the chosen
     * civil-date "today".
     *
     * @param edd       stored estimated due date (civil date, zoneless)
     * @param today     civil-date anchor ({@code X-Client-Date} or server UTC civil date)
     * @param lifecycle the profile's lifecycle string (e.g. {@code "pregnant"})
     * @return the computed snapshot; all fields are derived, nothing stored
     */
    public static GestationalAge compute(LocalDate edd, LocalDate today, String lifecycle) {
        long daysUntilEdd = ChronoUnit.DAYS.between(today, edd); // >0 future, 0 due today, <0 past EDD
        long daysPregnant = 280L - daysUntilEdd;                 // 280 = full term; EDD sits at EXACTLY day 280

        // MANDATORY: floorDiv/floorMod — raw / and % are FORBIDDEN (negative daysPregnant drifts).
        // Cast to int: the valid window daysPregnant ∈ [−28, 308] fits comfortably in int.
        int gestationalWeek = (int) Math.floorDiv(daysPregnant, 7L);  // COMPLETED weeks; floor toward −∞
        int gestationalDay  = (int) Math.floorMod(daysPregnant, 7L);  // 0..6 Euclidean remainder

        long daysRemaining = daysUntilEdd;                        // negative once past EDD

        // REAL division (÷ 280.0) — integer division would collapse 0..279 to 0
        double progress = Math.min(1.0, Math.max(0.0, daysPregnant / 280.0));

        // Stage band: edges use <= / >= so the clamp is structural (OQ-3a/b)
        String currentStage;
        if (gestationalWeek <= 13) {
            currentStage = "T1";   // includes wk 0 and any wk < 1 → clamps to T1, never blank
        } else if (gestationalWeek <= 27) {
            currentStage = "T2";
        } else {
            currentStage = "T3";   // includes wk 41, 42, beyond (past EDD) → still T3, neutral
        }

        // Delivery-window overlay — NEVER a stage value (B3)
        boolean deliveryWindowActive = "pregnant".equals(lifecycle) && gestationalWeek >= 37;

        return new GestationalAge(daysPregnant, gestationalWeek, gestationalDay,
                daysRemaining, progress, currentStage, deliveryWindowActive);
    }

    /**
     * Displayed gestational week: {@code max(0, gestationalWeek)}.
     *
     * <p>The upper end is NOT clamped (weeks 41, 42, 43… render faithfully while the stage stays T3).
     * The lower end is clamped to 0 because the negative-week band (EDD beyond the normal window)
     * must not show a misleading negative headline. The client additionally suppresses the
     * "Xw Yd" string entirely when {@code gestationalWeek < 0} (display-only concern — the raw
     * Euclidean values are returned in the server snapshot so the invariant holds).
     */
    public int displayedWeek() {
        return Math.max(0, gestationalWeek);
    }
}
