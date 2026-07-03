package com.momstarter.medication;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for {@code GET /medication-logs} read view (Slice 2 Task 4).
 *
 * <h3>Ciphertext posture (ADR Option A / spec §A.3 / RULING 1)</h3>
 * <p>{@link #note} is returned as an <strong>opaque Base64-encoded string</strong> (the raw
 * {@code bytea} column, Base64-encoded for JSON transport). The server
 * <strong>never decrypts</strong> this field; the client decodes and decrypts it.
 *
 * <h3>occurrenceTime — floating-civil display field (FLAG-1 / D5)</h3>
 * <p>{@link #occurrenceTime} is formatted as {@code "YYYY-MM-DDTHH:mm"} (minute-precision,
 * FLAG-1 convention). Stored as {@code timestamp WITHOUT TIME ZONE}; never UTC-normalised.
 * The DATE part is the calendar/adherence bucket for the client-side adherence count.
 *
 * <h3>loggedAt — absolute-UTC server-assigned instant</h3>
 * <p>{@link #loggedAt} is the server-assigned record-creation instant ({@code timestamptz}).
 * It is NOT the bucket key (that is {@link #occurrenceTime}) and NOT the LWW merge clock
 * (that is {@link #updatedAt}). Echoed on the response for client reference.
 *
 * <h3>Fields omitted (internal-only — not for the read surface)</h3>
 * <p>{@code userId} and {@code clientId} are internal fields not exposed by the read endpoint
 * (D7 / IDOR prevention / no client need on already-owned data).
 *
 * <h3>Null-omission</h3>
 * <p>Optional fields omitted from JSON when null ({@link #medicationPlanId}, {@link #note},
 * {@link #deletedAt}). Ad-hoc logs have {@code medicationPlanId = null} (E6 — legal).
 * Live rows have {@code deletedAt = null} (always omitted — soft-deleted rows are excluded).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MedicationLogResponse {

    /** Client-generated UUIDv4. */
    public final UUID id;

    /**
     * The plan this dose fulfils. Nullable ({@code null} = ad-hoc dose with no plan, E6).
     * Omitted when {@code null} by {@code @JsonInclude(NON_NULL)}.
     */
    public final UUID medicationPlanId;

    /**
     * Floating-civil calendar bucket key (FLAG-1 / D5) — {@code "YYYY-MM-DDTHH:mm"}.
     * The DATE part is the bucket for adherence views. Stored as
     * {@code timestamp WITHOUT TIME ZONE}; never UTC-normalised.
     */
    public final String occurrenceTime;

    /**
     * Two-state enum — {@code 'taken'} or {@code 'missed'} (plaintext).
     * {@code missed} is an equal-weight fact — NEVER interpreted as a health verdict (INV-M2).
     */
    public final String status;

    /**
     * Absolute-UTC record-creation instant. Server-assigned on INSERT; echoed on the response.
     * NOT the floating-civil bucket key ({@link #occurrenceTime}) and NOT the LWW clock
     * ({@link #updatedAt}).
     */
    public final Instant loggedAt;

    /**
     * Optional free-text note as Base64-encoded {@code bytea} (ciphertext, ADR Option A).
     * {@code null} when no note was captured — omitted by {@code @JsonInclude(NON_NULL)}.
     * Server never parses or interprets this field (INV-M3).
     */
    public final String note;

    // <sync> block (api-contract §1)

    /** Server-assigned monotonic optimistic-concurrency token. */
    public final Long version;

    /** Server-assigned insert instant (UTC). */
    public final Instant createdAt;

    /** Server-assigned last-mutation instant (UTC). Bumped only on INSERT and tombstone. */
    public final Instant updatedAt;

    /**
     * Soft-delete tombstone timestamp (UTC). Always {@code null} for live rows returned by
     * this endpoint ({@code deleted_at IS NULL} filtered in the repository). Omitted by
     * {@code @JsonInclude(NON_NULL)}.
     */
    public final Instant deletedAt;

    MedicationLogResponse(UUID id,
                          UUID medicationPlanId,
                          String occurrenceTime,
                          String status,
                          Instant loggedAt,
                          String note,
                          Long version,
                          Instant createdAt,
                          Instant updatedAt,
                          Instant deletedAt) {
        this.id = id;
        this.medicationPlanId = medicationPlanId;
        this.occurrenceTime = occurrenceTime;
        this.status = status;
        this.loggedAt = loggedAt;
        this.note = note;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }
}
