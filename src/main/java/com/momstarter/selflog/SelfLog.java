package com.momstarter.selflog;

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
 * SelfLog — immutable event record for self-reported health metrics (SD-5, Slice 1 Task 2).
 * Generic self-log capture (type b): weight · blood_pressure · swelling · lochia · symptom.
 * MOTHER-health collection.  Gated by {@code general_health} (per-collection) + {@code cloud_storage}
 * (whole-batch) on sync/push.
 *
 * <h3>id is CLIENT-GENERATED (NOT server-minted)</h3>
 * <p>No {@code @GeneratedValue}. Spring Data JPA's {@code isNew()} uses the {@link Long} wrapper
 * {@link #version} (null before first persist). See api-contract "Offline-sync engine §A.4 / data-model §2".
 *
 * <h3>Immutable event — create-only union (spec D2)</h3>
 * <p>Each self-log is a distinct UUIDv4; re-push of the same id is an idempotent no-op (echo current
 * version/updatedAt without overwriting fields). Updates are not accepted — a "correction" is a new row
 * (new UUID) and/or a tombstone of the old one. Tombstone-wins unconditionally on delete.
 * Mirrors {@link com.momstarter.kickcount.KickCountSession} — the declared sibling model.
 *
 * <h3>Encryption posture — ADR Option A (ADR self-log-encryption-posture.md Decision 1)</h3>
 * <p>{@link #valueNumeric}, {@link #valueNumericSecondary}, {@link #valueText}, {@link #noteCipher}
 * are {@code bytea} columns (SD-5 health values). MVP posture: hold PLAINTEXT bytes today
 * (no-op/passthrough cipher — KMS + AES-GCM EAS build is BLOCKED, HANDOFF §3). Real AES-GCM
 * ciphertext lands in THE SAME COLUMNS at the KMS/EAS milestone with ZERO schema change.
 * The server NEVER parses, queries, or aggregates these columns (G4 / INV-S2).
 *
 * <h3>Floating-civil timestamp — logged_at (FLAG-1 / D5)</h3>
 * <p>{@link #loggedAt} is stored as {@code timestamp WITHOUT TIME ZONE} — a civil wall-clock value,
 * never UTC-normalised. Its DATE part is the calendar bucket key for {@code GET /self-logs?from=&to=}
 * range filtering. Consistent with {@code kick_count_session.started_at} and {@code expense.incurred_on}.
 *
 * <h3>Crypto-shred on tombstone (§4.4(A) / PDPA ruling 5a)</h3>
 * <p>On tombstone (when {@link #deletedAt} is written), the apply path MUST set
 * {@link #valueNumeric}, {@link #valueNumericSecondary}, {@link #valueText}, and {@link #noteCipher}
 * to {@code null} — identical to {@code kick_count_session.note_cipher} shred behaviour.
 *
 * <h3>PDPA retention (§4.4)</h3>
 * <p>Tombstone GC after 180 days ({@code TOMBSTONE_TTL}). Per-account DEK crypto-shred on
 * {@code DELETE /account}. Registered in {@code TombstoneGcService.PURGE_TABLES} and
 * {@code AccountErasureService} Tier-1 purge.
 */
@Entity
@Table(name = "self_log")
public class SelfLog {

    /**
     * Client-generated UUIDv4. No {@code @GeneratedValue}.
     * Spring Data JPA's {@code isNew()} uses {@link #version} (null before first persist).
     */
    @Id
    private UUID id;

    /**
     * Owner user. FK → {@code users(id)}. NOT NULL.
     * (Note: the physical table is {@code users}, not {@code app_user} — ADR Decision 7 note 1.)
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Metric type — one of {@code weight | blood_pressure | swelling | lochia | symptom}.
     * Plaintext enum (the query/filter key — ADR Decision 2). DB CHECK is the enforcement backstop.
     * The server NEVER infers health state from this value (G4 / INV-S2); it is only a routing label.
     */
    @Column(name = "metric_type", nullable = false)
    private String metricType;

    /**
     * Primary numeric value (weight kg / BP systolic mmHg) — {@code bytea} ciphertext (SD-5).
     * MVP posture: holds PLAINTEXT bytes (no-op cipher; KMS/EAS blocked, HANDOFF §3).
     * Nullable: (a) only relevant per metricType; (b) crypto-shredded to NULL on tombstone (§4.4(A)).
     * NEVER parsed, queried, or aggregated server-side (G4 / INV-S2).
     */
    @Column(name = "value_numeric")
    private byte[] valueNumeric;

    /**
     * Secondary numeric value (BP diastolic mmHg; null for all non-{@code blood_pressure} metrics).
     * {@code bytea} ciphertext (SD-5); same posture as {@link #valueNumeric}.
     * Crypto-shredded to NULL on tombstone.
     */
    @Column(name = "value_numeric_secondary")
    private byte[] valueNumericSecondary;

    /**
     * Descriptive text value — swelling level / lochia description / symptom free-text.
     * {@code bytea} ciphertext (SD-5). Null for {@code weight} and {@code blood_pressure}.
     * PDF gate: {@code sensitive_lab_results} opt-in (ADR Decision 6 / spec §A.4).
     * Crypto-shredded to NULL on tombstone.
     */
    @Column(name = "value_text")
    private byte[] valueText;

    /**
     * Optional free-text note (any metricType). Client-encrypted opaque bytes.
     * {@code bytea} ciphertext (SD-5). NEVER parsed. Echoed back to client only.
     * PDF gate: {@code sensitive_lab_results} opt-in (spec §A.4 / ADR Decision 6).
     * Crypto-shredded to NULL on tombstone.
     * Mirrors {@link com.momstarter.kickcount.KickCountSession#noteCipher} exactly.
     */
    @Column(name = "note_cipher")
    private byte[] noteCipher;

    /**
     * Non-sensitive plaintext display label chosen by metricType.
     * {@code weight → "kg"}, {@code blood_pressure → "mmHg"}, others {@code null}.
     * Stored verbatim; the server NEVER keys on this column.
     * Nullable: swelling / lochia / symptom have no unit.
     */
    @Column
    private String unit;

    /**
     * Floating-civil calendar bucket key (FLAG-1 / D5).
     * {@code timestamp WITHOUT TIME ZONE} — NEVER UTC-normalised.
     * The DATE part is the bucket for {@code GET /self-logs?from=&to=} range filtering.
     * Ordering within a civil day; cross-device merge ordering uses {@link #updatedAt} only.
     * Corrects the Slice-1 plan's {@code text} type; database-schema §1.5 {@code timestamp} governs
     * (ADR Decision 7 note 2).
     */
    @Column(name = "logged_at", nullable = false)
    private LocalDateTime loggedAt;

    // -------------------------------------------------------------------------
    // <sync> block (data-model §1.2 / §2 / database-schema §0.3)
    // -------------------------------------------------------------------------

    /**
     * Server-assigned optimistic-concurrency token.
     * {@link Long} wrapper for Spring Data JPA's {@code isNew()} detection (null before first persist).
     * For immutable events: version:=1 on INSERT; bumped only on tombstone.
     */
    @Version
    private Long version;

    /** Server-assigned on first INSERT; never changed after. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Server-assigned on EVERY apply. Bumped only on INSERT and tombstone (immutable event).
     * The sole LWW merge clock for sync.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone. {@code null} = live. {@code non-null} = tombstoned (tombstone-wins).
     * On tombstone: {@link #valueNumeric}, {@link #valueNumericSecondary}, {@link #valueText},
     * and {@link #noteCipher} are crypto-shredded (set to {@code null}).
     * GC after {@code TOMBSTONE_TTL} (180 days, database-schema §4.4).
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Originating device UUID. LWW tie-break only (sync spec §A.6).
     * Nullable; absent = unknown device. No FK (multi-device; device rows not managed here).
     */
    @Column(name = "client_id")
    private UUID clientId;

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    /** Sets server-side timestamps on first persist. Does NOT generate {@code id}. */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    /** Stamps the server's current clock on every mutation (server-clock authority). */
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

    public String getMetricType() { return metricType; }
    public void setMetricType(String metricType) { this.metricType = metricType; }

    public byte[] getValueNumeric() { return valueNumeric; }
    public void setValueNumeric(byte[] valueNumeric) { this.valueNumeric = valueNumeric; }

    public byte[] getValueNumericSecondary() { return valueNumericSecondary; }
    public void setValueNumericSecondary(byte[] valueNumericSecondary) {
        this.valueNumericSecondary = valueNumericSecondary;
    }

    public byte[] getValueText() { return valueText; }
    public void setValueText(byte[] valueText) { this.valueText = valueText; }

    public byte[] getNoteCipher() { return noteCipher; }
    public void setNoteCipher(byte[] noteCipher) { this.noteCipher = noteCipher; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public LocalDateTime getLoggedAt() { return loggedAt; }
    public void setLoggedAt(LocalDateTime loggedAt) { this.loggedAt = loggedAt; }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
}
