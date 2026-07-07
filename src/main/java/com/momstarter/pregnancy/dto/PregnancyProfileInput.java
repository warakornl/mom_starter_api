package com.momstarter.pregnancy.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.Optional;

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
 *
 * <h2>Name fields — three-way null/absent/value semantics</h2>
 * <p>The three name cipher fields ({@code motherFirstName}, {@code motherLastName}, {@code babyName})
 * use the following contract (api-contract L681 / name-fields-design.md Decision 4):
 * <ul>
 *   <li><b>Absent key</b> (not in request JSON) → Java field stays {@code null} → leave column unchanged.</li>
 *   <li><b>Explicit JSON {@code null}</b> ({@code "motherFirstName": null}) → Java field =
 *       {@code Optional.empty()} → clear column to NULL.</li>
 *   <li><b>Value</b> ({@code "motherFirstName": "base64..."}) → Java field =
 *       {@code Optional.of("base64...")} → set / replace column.</li>
 * </ul>
 * This three-way semantics is needed because random-IV ciphertexts cannot be byte-diffed for
 * equality; a name-key present (with any value including null) is always a REAL mutation that
 * persists + bumps {@code version} even when {@code edd} is unchanged (OQ-9 scoped exception).
 *
 * <p>Implemented as a regular class (not a record) so that Jackson uses setter injection —
 * when a key is absent, the setter is never called and the field stays at its default ({@code null}).
 * A record would always carry all constructor args; distinguishing absent from null would require
 * a custom deserializer, which a setter-based class avoids.
 */
public class PregnancyProfileInput {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate edd;

    private Integer currentWeek;

    // null = key absent in JSON (leave unchanged)
    // Optional.empty() = explicit JSON null (clear to NULL)
    // Optional.of(value) = has ciphertext (set/replace)
    private Optional<String> motherFirstName;
    private Optional<String> motherLastName;
    private Optional<String> babyName;

    /** Default constructor required by Jackson. */
    public PregnancyProfileInput() {}

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public LocalDate getEdd() {
        return edd;
    }

    public Integer getCurrentWeek() {
        return currentWeek;
    }

    /**
     * Returns {@code null} if {@code motherFirstName} was absent from the request JSON,
     * {@code Optional.empty()} if it was explicitly sent as JSON {@code null},
     * or {@code Optional.of(base64)} if it carried a ciphertext value.
     */
    public Optional<String> getMotherFirstName() {
        return motherFirstName;
    }

    /** Same three-way semantics as {@link #getMotherFirstName()}. */
    public Optional<String> getMotherLastName() {
        return motherLastName;
    }

    /** Same three-way semantics as {@link #getMotherFirstName()}. */
    public Optional<String> getBabyName() {
        return babyName;
    }

    // -------------------------------------------------------------------------
    // Setters (called by Jackson when the key is present in the JSON)
    // -------------------------------------------------------------------------

    @JsonProperty("edd")
    public void setEdd(LocalDate edd) {
        this.edd = edd;
    }

    @JsonProperty("currentWeek")
    public void setCurrentWeek(Integer currentWeek) {
        this.currentWeek = currentWeek;
    }

    /**
     * Called by Jackson when the {@code motherFirstName} key is present in the JSON (even if null).
     * Absent key → this method is never called → {@link #motherFirstName} stays {@code null}.
     */
    @JsonProperty("motherFirstName")
    public void setMotherFirstName(String value) {
        this.motherFirstName = Optional.ofNullable(value);
    }

    /** See {@link #setMotherFirstName(String)}. */
    @JsonProperty("motherLastName")
    public void setMotherLastName(String value) {
        this.motherLastName = Optional.ofNullable(value);
    }

    /** See {@link #setMotherFirstName(String)}. */
    @JsonProperty("babyName")
    public void setBabyName(String value) {
        this.babyName = Optional.ofNullable(value);
    }
}
