package com.momstarter.selflog;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for {@code GET /self-logs} history view (Slice 1 Task 4).
 *
 * <h3>Ciphertext posture (ADR Option A / spec §A.2)</h3>
 * <p>The four value/note fields ({@link #valueNumeric}, {@link #valueNumericSecondary},
 * {@link #valueText}, {@link #note}) are returned as <strong>opaque Base64-encoded bytes</strong>
 * (the raw {@code bytea} contents, Base64-encoded for JSON transport). The server
 * <strong>never decrypts</strong> these bytes server-side; the client decodes and decrypts them.
 * Consistent with the {@code note} field on {@code GET /kick-count-sessions} and the
 * {@code amount}/{@code note} fields on {@code GET /expenses}.
 *
 * <h3>Plaintext fields</h3>
 * <p>{@link #metricType}, {@link #unit}, {@link #loggedAt} (floating-civil bucket key, FLAG-1),
 * and the {@code <sync>} block ({@link #version}, {@link #createdAt}, {@link #updatedAt},
 * {@link #deletedAt}) are returned as plaintext — they are not health values (ADR Decision 2).
 *
 * <h3>Null-omission</h3>
 * <p>Optional fields omitted from the JSON when null (e.g. {@link #valueNumeric} for a
 * {@code swelling}/{@code lochia}/{@code symptom} log; {@link #note} when no free-text note
 * was captured).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SelfLogResponse {

    /** Client-generated UUIDv4. */
    public final UUID id;

    /**
     * Metric type — one of {@code weight | blood_pressure | swelling | lochia | symptom}.
     * Plaintext. The server never infers health state from this value (INV-S2 / G4).
     */
    public final String metricType;

    /**
     * Primary numeric value (weight kg / BP systolic) as Base64-encoded {@code bytea}.
     * Null for {@code swelling}/{@code lochia}/{@code symptom} and on tombstoned rows.
     * Client decodes and decrypts; server never reads the content.
     */
    public final String valueNumeric;

    /**
     * Secondary numeric value (BP diastolic) as Base64-encoded {@code bytea}.
     * Null for all non-{@code blood_pressure} metrics.
     */
    public final String valueNumericSecondary;

    /**
     * Descriptive text value (swelling/lochia/symptom) as Base64-encoded {@code bytea}.
     * Null for {@code weight} and {@code blood_pressure}.
     * PDF gate: {@code sensitive_lab_results} opt-in (spec §A.4 / ADR Decision 6).
     */
    public final String valueText;

    /**
     * Non-sensitive display label: {@code "kg"} (weight), {@code "mmHg"} (BP), or null.
     * Plaintext. Server never keys on this column.
     */
    public final String unit;

    /**
     * Floating-civil wall-clock bucket key (FLAG-1 / D5) — {@code "YYYY-MM-DDTHH:mm"}.
     * The DATE part is the calendar bucket for day-detail views.
     * Stored as {@code timestamp WITHOUT TIME ZONE}; never UTC-normalised.
     */
    public final String loggedAt;

    /**
     * Optional free-text note as Base64-encoded {@code bytea} (the {@code note_cipher} column).
     * Null when no note was captured. Server never parses or interprets this field (INV-S4).
     * PDF gate: {@code sensitive_lab_results} opt-in (spec §A.4).
     */
    public final String note;

    // <sync> block (api-contract §1)

    /** Server-assigned monotonic optimistic-concurrency token. */
    public final Long version;

    /** Server-assigned insert instant (UTC). */
    public final Instant createdAt;

    /** Server-assigned last-mutation instant (UTC). The sole LWW merge clock. */
    public final Instant updatedAt;

    /**
     * Soft-delete tombstone timestamp (UTC). {@code null} for live rows.
     * Live rows are the only rows returned by this endpoint ({@code deleted_at IS NULL}
     * filtered in the repository), so this field is always {@code null} in practice
     * (and therefore omitted from the JSON by {@code @JsonInclude(NON_NULL)}).
     */
    public final Instant deletedAt;

    SelfLogResponse(UUID id,
                    String metricType,
                    String valueNumeric,
                    String valueNumericSecondary,
                    String valueText,
                    String unit,
                    String loggedAt,
                    String note,
                    Long version,
                    Instant createdAt,
                    Instant updatedAt,
                    Instant deletedAt) {
        this.id = id;
        this.metricType = metricType;
        this.valueNumeric = valueNumeric;
        this.valueNumericSecondary = valueNumericSecondary;
        this.valueText = valueText;
        this.unit = unit;
        this.loggedAt = loggedAt;
        this.note = note;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }
}
