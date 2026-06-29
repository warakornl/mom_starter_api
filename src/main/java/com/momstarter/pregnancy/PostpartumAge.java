package com.momstarter.pregnancy;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Canonical postpartum-age computation — PINNED (data-model §3.1 /
 * api-contract "Birth-event &amp; postpartum counting").
 *
 * <p><strong>Frozen shared algorithm.</strong> Both {@code springboot-backend-dev} (response snapshot)
 * and {@code rn-mobile-dev} (on-device daily counter) MUST implement this EXACT algorithm,
 * byte-identically — frozen the same way the gestational formula in {@link GestationalAge}
 * is frozen, so server and client can never disagree on the postpartum week. Canonical home:
 * {@code data-model.md} §3.1 "Birth-event &amp; postpartum counting"; this class is its
 * backend implementation.
 *
 * <p><strong>Algorithm (CANONICAL):</strong>
 * <pre>
 *   postpartumDays = max(0, civilDaysBetween(birthDate, today))
 *   postpartumWeek = floorDiv(postpartumDays, 7)   // COMPLETED weeks since birth; week 0 = first week
 *   postpartumDay  = floorMod(postpartumDays, 7)   // 0..6 Euclidean day-in-week
 * </pre>
 *
 * <p><strong>MANDATORY: {@link Math#floorDiv} / {@link Math#floorMod}, never raw {@code /}
 * or {@code %}</strong>. Although the clamp {@code max(0, ...)} removes negatives, the contract
 * mandates {@code floorDiv}/{@code floorMod} for <em>parity</em> with the gestational counter
 * (same invariant, same operator contract). Invariant: {@code postpartumWeek*7 + postpartumDay
 * == postpartumDays} MUST hold for every input.
 *
 * <p><strong>"today" authority.</strong> Client = the device local civil date (display authority).
 * Server snapshot = the client-supplied {@code X-Client-Date} civil date when present, else the
 * server's UTC civil date (advisory only — same OQ-2 rule as the gestational counter).
 *
 * @param postpartumDays  {@code max(0, civilDaysBetween(birthDate, today))} — 0 on the birth day
 * @param postpartumWeek  {@code floorDiv(postpartumDays, 7)} — COMPLETED weeks since birth (week 0 = first week)
 * @param postpartumDay   {@code floorMod(postpartumDays, 7)} — 0..6 Euclidean day-in-week
 */
public record PostpartumAge(
        int postpartumDays,
        int postpartumWeek,
        int postpartumDay) {

    /**
     * Computes the postpartum-age snapshot from the stored {@code birthDate} and the
     * chosen civil-date "today".
     *
     * @param birthDate floating-civil birth date ({@code YYYY-MM-DD}, zoneless)
     * @param today     civil-date anchor ({@code X-Client-Date} or server UTC civil date)
     * @return the computed snapshot; all fields are derived, nothing stored
     */
    public static PostpartumAge compute(LocalDate birthDate, LocalDate today) {
        // civilDaysBetween(birthDate, today) = today − birthDate in calendar days.
        // Clamped to ≥ 0: guards a device whose local "today" briefly trails a birthDate
        // recorded on another device/zone (data-model OQ-11 "clamp guards" note).
        long rawDays = ChronoUnit.DAYS.between(birthDate, today);
        int postpartumDays = (int) Math.max(0L, rawDays);

        // MANDATORY: floorDiv/floorMod — raw / and % are forbidden, for parity with the
        // gestational counter (even though postpartumDays >= 0, the operator is mandated).
        int postpartumWeek = Math.floorDiv(postpartumDays, 7);  // COMPLETED weeks
        int postpartumDay  = Math.floorMod(postpartumDays, 7);  // 0..6 Euclidean remainder

        return new PostpartumAge(postpartumDays, postpartumWeek, postpartumDay);
    }
}
