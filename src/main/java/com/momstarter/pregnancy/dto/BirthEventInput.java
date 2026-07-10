package com.momstarter.pregnancy.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.Optional;

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
 * <h2>Hospital-stay cipher fields — three-way null/absent/value semantics</h2>
 * <p>{@code hospitalAdmissionDate} and {@code hospitalDischargeDate} are client-encrypted
 * Base64 ciphertexts (Option A — pregnancy-summary-design.md §1.2) that use the same
 * three-way contract as the name cipher fields (api-contract L227 / pregnancy-summary-design.md §1.3):
 * <ul>
 *   <li><b>Absent key</b> (not in request JSON) → Java field stays {@code null} → leave column unchanged.</li>
 *   <li><b>Explicit JSON {@code null}</b> ({@code "hospitalAdmissionDate": null}) → Java field =
 *       {@code Optional.empty()} → clear column to NULL.</li>
 *   <li><b>Value</b> ({@code "hospitalAdmissionDate": "base64..."}) → Java field =
 *       {@code Optional.of("base64...")} → set / replace column.</li>
 * </ul>
 *
 * <p>Presence-of-key (value OR explicit null) suppresses the no-op short-circuit for
 * already-postpartum re-POSTs with the same birthDate (contract L227 load-bearing pin).
 * This ensures that recording a hospital date days after the birth event is NOT swallowed
 * as a no-op. The server NEVER parses or temporally-validates the date bytes; temporal
 * validation is client-side only (pregnancy-summary-design.md §1.3 / OQ-PS4).
 *
 * <p>Implemented as a regular class (not a record) so that Jackson uses setter injection —
 * when a key is absent, the setter is never called and the field stays at its default
 * ({@code null}). A record would require a custom deserializer to distinguish absent from
 * explicit null.
 *
 * <h2>TODO (security-compliance carry-forward)</h2>
 * <p>The api-contract marks {@code deliveryType} and {@code birthNote} as "client-encrypted
 * {@code bytea}" fields. For the test phase they are stored as <strong>plaintext</strong> in
 * the existing {@code varchar(64)} / {@code text} columns. When the encryption feature ships:
 * <ol>
 *   <li>Replace these two fields with cipher-text byte arrays (or Base64 strings).</li>
 *   <li>Migrate the {@code delivery_type} / {@code birth_note} columns to {@code bytea}.</li>
 *   <li>Update the no-op equality check (already correct — it compares {@code birthDate}
 *       only, never the cipher fields, per OQ-12/PP6).</li>
 * </ol>
 * Tag: {@code security-compliance PDPA s.26 field-encryption deferred}.
 */
public class BirthEventInput {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate birthDate;

    private String deliveryType;

    private String birthNote;

    // null = key absent in JSON (leave unchanged)
    // Optional.empty() = explicit JSON null (clear to NULL)
    // Optional.of(base64) = ciphertext value (set/replace)
    private Optional<String> hospitalAdmissionDate;
    private Optional<String> hospitalDischargeDate;

    /** Default constructor required by Jackson. */
    public BirthEventInput() {}

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /**
     * Civil birth date ({@code YYYY-MM-DD}, required).
     * Returns {@code null} when the key was absent or explicitly null in the JSON.
     * The service validates non-null and temporal constraints.
     */
    public LocalDate getBirthDate() {
        return birthDate;
    }

    /**
     * Delivery type (e.g. {@code "vaginal"}, {@code "cesarean"}). Optional, free-value;
     * never parsed or interpreted by the server.
     */
    public String getDeliveryType() {
        return deliveryType;
    }

    /**
     * Free-text birth note. Optional; language-agnostic; never parsed.
     */
    public String getBirthNote() {
        return birthNote;
    }

    /**
     * Returns {@code null} if {@code hospitalAdmissionDate} was absent from the request JSON,
     * {@code Optional.empty()} if it was explicitly sent as JSON {@code null} (clear operation),
     * or {@code Optional.of(base64)} if it carried a ciphertext value.
     *
     * <p>Non-null (present in JSON) → suppresses the no-op short-circuit on already-postpartum
     * re-POSTs (contract L227).
     */
    public Optional<String> getHospitalAdmissionDate() {
        return hospitalAdmissionDate;
    }

    /**
     * Same three-way semantics as {@link #getHospitalAdmissionDate()}.
     */
    public Optional<String> getHospitalDischargeDate() {
        return hospitalDischargeDate;
    }

    // -------------------------------------------------------------------------
    // Setters (called by Jackson when the key is present in the JSON)
    // -------------------------------------------------------------------------

    /** Called by Jackson when the {@code birthDate} key is present in the JSON. */
    @JsonProperty("birthDate")
    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    /** Called by Jackson when the {@code deliveryType} key is present (even if null). */
    @JsonProperty("deliveryType")
    public void setDeliveryType(String deliveryType) {
        this.deliveryType = deliveryType;
    }

    /** Called by Jackson when the {@code birthNote} key is present (even if null). */
    @JsonProperty("birthNote")
    public void setBirthNote(String birthNote) {
        this.birthNote = birthNote;
    }

    /**
     * Called by Jackson when the {@code hospitalAdmissionDate} key is present in the JSON.
     * Absent key → this method is never called → {@link #hospitalAdmissionDate} stays {@code null}.
     * JSON null → {@code Optional.empty()} (clear). JSON value → {@code Optional.of(value)} (set).
     */
    @JsonProperty("hospitalAdmissionDate")
    public void setHospitalAdmissionDate(String value) {
        this.hospitalAdmissionDate = Optional.ofNullable(value);
    }

    /**
     * See {@link #setHospitalAdmissionDate(String)}.
     */
    @JsonProperty("hospitalDischargeDate")
    public void setHospitalDischargeDate(String value) {
        this.hospitalDischargeDate = Optional.ofNullable(value);
    }
}
