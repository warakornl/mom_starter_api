package com.momstarter.medication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MedicationLog — an immutable event record for a taken/missed dose (SD-2, Slice 2).
 *
 * <p><strong>IMMUTABLE EVENT pattern</strong> — mirrors {@link com.momstarter.selflog.SelfLog}
 * and {@code KickCountSession}. Each log is a distinct client UUIDv4; re-push of the same id
 * is an idempotent no-op (echo current version/updatedAt, no overwrite, version NOT bumped).
 * Updates are not accepted — a "correction" is a NEW row (new UUID) and/or a tombstone of the
 * old one. Tombstone-wins unconditionally on soft-delete.
 *
 * <h3>id is CLIENT-GENERATED (NOT server-minted)</h3>
 * <p>No {@code @GeneratedValue}. Spring Data JPA's {@code isNew()} uses the {@link Long}
 * wrapper {@link #version} (null before first persist).
 *
 * <h3>occurrenceTime — floating-civil bucket key (FLAG-1 / D5)</h3>
 * <p>{@link #occurrenceTime} is {@code timestamp WITHOUT TIME ZONE} — a civil wall-clock value,
 * NEVER UTC-normalised. Its DATE part is the calendar/adherence bucket key for
 * {@code GET /medication-logs?from=&to=} range filtering and the client-side adherence count
 * (RULING 7.2/7.3). This mirrors {@link com.momstarter.selflog.SelfLog#loggedAt}'s type exactly.
 *
 * <h3>loggedAt — absolute-UTC server-assigned instant (NOT in MedicationLogInput)</h3>
 * <p>{@link #loggedAt} is {@code timestamptz} — the server-assigned record-creation instant
 * (D5). It is NOT in {@code MedicationLogInput} and is NEVER a bucket key. Echoed on the
 * response. Assigned in {@link #onCreate()} alongside {@link #createdAt}.
 *
 * <h3>medicationPlanId — nullable hard FK</h3>
 * <p>{@link #medicationPlanId} is a nullable {@code uuid} referencing
 * {@code medication_plan(id)} (hard FK — RULING 6). {@code null} = ad-hoc dose with no plan
 * (legal, E6). When present, the apply path verifies ownership (D7 / §A.1 §G-4).
 *
 * <h3>Encryption posture — ADR Option A (RULING 1)</h3>
 * <p>{@link #noteCipher} is a {@code bytea} column. MVP: PLAINTEXT bytes (no-op cipher;
 * KMS/EAS blocked, HANDOFF §3). Real AES-GCM at KMS/EAS milestone — same column, zero schema
 * change. Server NEVER parses this column (INV-M3 / G4).
 *
 * <h3>Crypto-shred on tombstone (§4.4(A) / PDPA ruling 5a)</h3>
 * <p>On tombstone, the apply path MUST set {@link #noteCipher} to {@code null}.
 *
 * <h3>Tier-1 erasure (RULING 4)</h3>
 * <p>Registered in {@code AccountErasureService.TIER1_CHILD_DELETE_ORDER} BEFORE
 * {@code medication_plan} (this table references it via a hard FK — deleting plans first
 * would FK-violate surviving log rows).
 */
@Entity
@Table(name = "medication_log")
public class MedicationLog {

    /**
     * Client-generated UUIDv4. No {@code @GeneratedValue}.
     * Spring Data JPA's {@code isNew()} uses {@link #version} (null before first persist).
     */
    @Id
    private UUID id;

    /**
     * Owner user. FK → {@code users(id)}. NOT NULL.
     * (Physical table is {@code users}; {@code app_user} was never created — RULING 6.)
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * The plan this dose fulfils. Nullable {@code uuid} → FK {@code medication_plan(id)}
     * (hard FK — RULING 6; nullable = ad-hoc dose, E6). When present, apply path verifies
     * ownership (D7 / §A.1): referenced plan must be a live row owned by the subject,
     * else {@code validation_error(medication_plan_not_found)}. No JPA relationship — kept
     * as a plain {@code uuid} to avoid accidental eager-loading and to mirror the sync-engine
     * posture (ids only on the wire).
     */
    @Column(name = "medication_plan_id")
    private UUID medicationPlanId;

    /**
     * Floating-civil calendar bucket key (FLAG-1 / D5).
     * {@code timestamp WITHOUT TIME ZONE} — NEVER UTC-normalised.
     * The DATE part is the bucket for {@code GET /medication-logs?from=&to=} range filtering
     * and the client-side adherence count (RULING 7.2/7.3).
     * Maps exactly like {@link com.momstarter.selflog.SelfLog#loggedAt} (same Hibernate
     * mapping for {@code timestamp WITHOUT TIME ZONE}).
     */
    @Column(name = "occurrence_time", nullable = false)
    private LocalDateTime occurrenceTime;

    /**
     * Two-state enum — {@code 'taken'} or {@code 'missed'} (plaintext).
     * DB CHECK backstop: {@code CHECK (status IN ('taken', 'missed'))}.
     * {@code missed} is an equal-weight fact — NEVER interpreted as a health verdict or
     * failure surface (INV-M2 / capture-ui §3.1 / AC-20).
     */
    @Column(nullable = false)
    private String status;

    /**
     * Absolute-UTC record-creation instant. Server-assigned in {@link #onCreate()}.
     * NOT in {@code MedicationLogInput} (D5). NOT the floating-civil bucket key
     * (that is {@link #occurrenceTime}). NOT the LWW clock (that is {@link #updatedAt}).
     * Echoed on the response as {@code loggedAt} (contract MedicationLogInput).
     */
    @Column(name = "logged_at", nullable = false)
    private Instant loggedAt;

    /**
     * Optional free-text note — {@code bytea} ciphertext (SD-2, Option A).
     * MVP: PLAINTEXT bytes (no-op cipher). Genuinely optional (nullable, no guard).
     * Server NEVER parses or queries this value (INV-M3 / G4).
     * Crypto-shredded to null on tombstone (§4.4(A)).
     * PDF gate: {@code sensitive_lab_results} (spec §A.6 / ADR RULING 1).
     */
    @Column(name = "note_cipher")
    private byte[] noteCipher;

    // -------------------------------------------------------------------------
    // <sync> block (data-model §1.2 / §2 / database-schema §0.3)
    // -------------------------------------------------------------------------

    /**
     * Server-assigned optimistic-concurrency token.
     * {@link Long} wrapper for Spring Data JPA's {@code isNew()} detection (null before first persist).
     * For immutable events: version:=1 on INSERT; bumped ONLY on tombstone (never on re-push no-op).
     */
    @Version
    private Long version;

    /** Server-assigned on first INSERT; never changed after. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Server-assigned on EVERY apply. Bumped only on INSERT and tombstone (immutable event).
     * Re-push of same id (idempotent no-op) does NOT bump this field.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone. {@code null} = live. {@code non-null} = tombstoned.
     * On tombstone: {@link #noteCipher} is crypto-shredded (set to {@code null}).
     * GC after {@code TOMBSTONE_TTL} (180 days).
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Originating device UUID. LWW tie-break only (sync spec §A.6). Nullable. No FK.
     */
    @Column(name = "client_id")
    private UUID clientId;

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    /**
     * Sets server-side timestamps on first persist. Does NOT generate {@code id}.
     * Assigns {@link #loggedAt} (server-owned creation instant, never from client).
     */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (loggedAt == null) {
            loggedAt = now;
        }
        updatedAt = now;
    }

    /**
     * Stamps the server's current clock on every mutation (server-clock authority).
     * {@link #loggedAt} is intentionally NOT updated here — it is fixed at creation.
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getMedicationPlanId() { return medicationPlanId; }
    public void setMedicationPlanId(UUID medicationPlanId) {
        this.medicationPlanId = medicationPlanId;
    }

    public LocalDateTime getOccurrenceTime() { return occurrenceTime; }
    public void setOccurrenceTime(LocalDateTime occurrenceTime) {
        this.occurrenceTime = occurrenceTime;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getLoggedAt() { return loggedAt; }

    public byte[] getNoteCipher() { return noteCipher; }
    public void setNoteCipher(byte[] noteCipher) { this.noteCipher = noteCipher; }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
}
