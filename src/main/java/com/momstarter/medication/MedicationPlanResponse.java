package com.momstarter.medication;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for {@code GET /medication-plans} read view (Slice 2 Task 4).
 *
 * <h3>Ciphertext posture (ADR Option A / spec §A.2 / RULING 1)</h3>
 * <p>{@link #name} and {@link #dose} are returned as <strong>opaque Base64-encoded bytes</strong>
 * (the raw {@code bytea} columns, Base64-encoded for JSON transport). The server
 * <strong>never decrypts</strong> these bytes server-side; the client decodes and decrypts them.
 *
 * <h3>scheduleRule — embedded JSON object</h3>
 * <p>{@link #scheduleRule} is the FLAG-4 recurrence grammar stored as {@code jsonb}. It is
 * re-parsed from the stored JSON string to a {@link JsonNode} so Jackson serialises it as a
 * nested JSON object on the wire (not as a string literal). {@code null} for PRN/ad-hoc plans.
 *
 * <h3>Fields omitted (internal-only — not for the read surface)</h3>
 * <p>{@code userId}, {@code clientId}, and {@code sourceSuggestionStateId} are internal LWW
 * fields not exposed by the read endpoint (D7 / IDOR prevention / no client need).
 *
 * <h3>Null-omission</h3>
 * <p>Optional fields omitted from the JSON when null ({@link #dose}, {@link #scheduleRule},
 * {@link #deletedAt}). Live rows always have {@link #name} non-null (guarded by
 * {@code ck_medication_plan__live_name} DB CHECK). {@link #deletedAt} is always null for the
 * live-only read surface (soft-deleted rows are excluded by the repository query).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MedicationPlanResponse {

    /** Client-generated UUIDv4. */
    public final UUID id;

    /**
     * Drug/supplement name as Base64-encoded {@code bytea} (ciphertext, ADR Option A).
     * Non-null for all live plans (guarded by {@code ck_medication_plan__live_name} CHECK).
     * Server never decrypts; client decodes and decrypts (INV-M3).
     */
    public final String name;

    /**
     * Dose string as Base64-encoded {@code bytea} (ciphertext, ADR Option A). Nullable —
     * dose is genuinely optional (no live-guard CHECK). Omitted when null (NON_NULL).
     * Server never decrypts (INV-M3).
     */
    public final String dose;

    /**
     * FLAG-4 recurrence grammar (RULING 7.1) returned as an embedded JSON object.
     * {@code null} for PRN/ad-hoc plans (no recurring schedule, M=0 for adherence).
     * Server validates grammar on push; never expands for adherence (INV-M3).
     * Re-parsed from the stored JSON string to avoid string double-encoding on the wire.
     */
    public final JsonNode scheduleRule;

    /**
     * Whether this plan is an active reminder source. Plaintext boolean (non-sensitive).
     * A single LWW boolean — not time-versioned.
     */
    public final boolean active;

    // <sync> block (api-contract §1)

    /** Server-assigned monotonic optimistic-concurrency token. */
    public final Long version;

    /** Server-assigned insert instant (UTC). */
    public final Instant createdAt;

    /** Server-assigned last-mutation instant (UTC). The sole LWW merge clock. */
    public final Instant updatedAt;

    /**
     * Soft-delete tombstone timestamp (UTC). Always {@code null} for live rows returned by
     * this endpoint ({@code deleted_at IS NULL} filtered in the repository). Omitted by
     * {@code @JsonInclude(NON_NULL)}.
     */
    public final Instant deletedAt;

    MedicationPlanResponse(UUID id,
                           String name,
                           String dose,
                           JsonNode scheduleRule,
                           boolean active,
                           Long version,
                           Instant createdAt,
                           Instant updatedAt,
                           Instant deletedAt) {
        this.id = id;
        this.name = name;
        this.dose = dose;
        this.scheduleRule = scheduleRule;
        this.active = active;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }
}
