package com.momstarter.pregnancy.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/**
 * Request body for {@code POST /pregnancy-profile/birth-event}.
 *
 * <p>{@code birthDate} is REQUIRED (api-contract {@code BirthEventInput {birthDate R, ...}}).
 * A null or missing {@code birthDate} results in a {@code 422 validation_error} returned
 * by {@link com.momstarter.pregnancy.PregnancyProfileService#recordBirthEvent}.
 *
 * <p>{@code deliveryType} and {@code birthNote} are optional free-value/free-text fields
 * stored verbatim in the {@code delivery_type} / {@code birth_note} columns.
 *
 * <p><strong>TODO (security-compliance carry-forward):</strong> The api-contract marks
 * {@code deliveryType} and {@code birthNote} as "client-encrypted {@code bytea}" fields.
 * For the test phase they are stored as <strong>plaintext</strong> in the existing
 * {@code varchar(64)} / {@code text} columns. When the encryption feature ships:
 * <ol>
 *   <li>Replace these fields with cipher-text byte arrays (or Base64 strings).</li>
 *   <li>Migrate the {@code delivery_type} / {@code birth_note} columns to {@code bytea}.</li>
 *   <li>Update the no-op equality check (already correct — it compares {@code birthDate}
 *       only, never the cipher fields, per OQ-12/PP6).</li>
 * </ol>
 * Tag: {@code security-compliance PDPA s.26 field-encryption deferred}.
 */
public record BirthEventInput(
        /** Civil birth date ({@code YYYY-MM-DD}, required). */
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate birthDate,

        /**
         * Delivery type (e.g. {@code "vaginal"}, {@code "cesarean"}). Optional, free-value;
         * never parsed or interpreted by the server.
         *
         * <p>TODO security-compliance: store as client-encrypted {@code bytea} (OQ-12 / api-contract
         * "Birth-event &amp; postpartum counting"). Plaintext for MVP test phase.
         */
        String deliveryType,

        /**
         * Free-text birth note. Optional; language-agnostic; never parsed.
         *
         * <p>TODO security-compliance: store as client-encrypted {@code bytea}. Plaintext for MVP test phase.
         */
        String birthNote) {
}
