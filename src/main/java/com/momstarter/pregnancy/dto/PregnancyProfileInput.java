package com.momstarter.pregnancy.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/**
 * PUT /pregnancy-profile request body.
 *
 * <p>Exactly ONE of {@code edd} or {@code currentWeek} MUST be provided (XOR).
 * Both present or both absent → 422 validation_error.
 * Bean-Validation cannot express XOR, so the check is performed in
 * {@link com.momstarter.pregnancy.PregnancyProfileService}.
 *
 * <ul>
 *   <li>{@code edd} — civil date in ISO-8601 format {@code YYYY-MM-DD}, entered directly.</li>
 *   <li>{@code currentWeek} — gestational week (completed weeks, 0-based). The server back-computes
 *       {@code edd = today + (280 − currentWeek * 7)} using the {@code X-Client-Date} header (or the
 *       server's UTC civil date as fallback). OQ-7.</li>
 * </ul>
 */
public record PregnancyProfileInput(
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate edd,
        Integer currentWeek) {
}
