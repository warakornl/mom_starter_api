package com.momstarter.reminder;

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
 * ReminderOccurrence — a fired-instance record for a {@link Reminder} (data-model §3.5, US-2).
 *
 * <p>MOTHER-health collection; gated by {@code general_health} (per-collection) +
 * {@code cloud_storage} (whole-batch) on {@code sync/push} / {@code sync/pull}.
 *
 * <h3>W-A SPARSE TABLE (OQ-CAL-4 — PINNED)</h3>
 * <p>This table holds ONLY terminal user-action rows: {@code done} and {@code snoozed}.
 * A {@code due} instance is projected locally from the {@link Reminder} definition via the
 * FLAG-4 expansion algorithm — it costs ZERO rows.  A {@code missed} status is derived
 * on-device at end-of-local-day and is NOT pushed in MVP.
 * The {@code status} CHECK still includes all four values for the local store and
 * read-only GET paths (forward-compatibility for future {@code missed}-pushing clients).
 *
 * <h3>Deterministic id (N6/N7 — PINNED)</h3>
 * <p>{@code id = uuidv5(OCCURRENCE_NAMESPACE, reminderId + "|" + scheduledLocalCivil)},
 * where {@code scheduledLocalCivil} is {@code "YYYY-MM-DDTHH:mm"} (minute precision,
 * no zone) and {@code OCCURRENCE_NAMESPACE = 4328078f-6339-4c38-a2ce-eabff6cbf387}.
 * The server RECOMPUTES the id on {@code sync/push} and rejects a mismatch (422).
 * See {@link com.momstarter.occurrence.OccurrenceId#compute} for the canonical implementation.
 * No {@code @GeneratedValue} — the client computes and supplies this id.
 *
 * <h3>Floating-civil scheduled_local_time (FLAG-1)</h3>
 * <p>{@link LocalDateTime} → {@code timestamp WITHOUT TIME ZONE}.
 * Must be minute-precision (SECOND = 0) — enforced by {@code ck_reminder_occurrences__minute}
 * and validated (422) on {@code sync/push}.
 *
 * <h3>Soft link (no FK)</h3>
 * <p>{@link #reminderId} is a soft link — no FK to {@code reminders}.  Tombstoning the parent
 * {@link Reminder} does NOT cascade; past {@code done}/{@code snoozed} rows are retained as
 * adherence history (OQ-CAL-6).
 *
 * <h3>M1 status-merge precedence</h3>
 * <p>Explicit {@code done}/{@code snoozed} always outranks a derived {@code missed} for the
 * same id, regardless of timestamp.  Enforced in the sync apply path
 * ({@link com.momstarter.occurrence.StatusMerge}), not in DB constraints.
 */
@Entity
@Table(name = "reminder_occurrences")
public class ReminderOccurrence {

    /**
     * Deterministic UUIDv5 — CLIENT-COMPUTED.  No {@code @GeneratedValue}.
     * Spring Data JPA's {@code isNew()} uses {@link #version} (null before first persist).
     */
    @Id
    private UUID id;

    /** Owner user. FK → {@code users(id)}. NOT NULL. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Soft link to the parent {@link Reminder}.  NO FK — orphan-tolerant (OQ-CAL-6).
     * NOT NULL: every occurrence is generated from a specific Reminder definition.
     * A row whose {@code reminderId} references a tombstoned Reminder is valid.
     */
    @Column(name = "reminder_id", nullable = false)
    private UUID reminderId;

    /**
     * Floating-civil occurrence civil datetime (FLAG-1).
     * {@link LocalDateTime} → {@code timestamp WITHOUT TIME ZONE} — never UTC-normalized.
     * Format: {@code YYYY-MM-DDTHH:mm} (minute precision, no zone/offset).
     * This exact string feeds the uuidv5 derivation: mismatch → 422 on push.
     * DB constraint {@code ck_reminder_occurrences__minute} enforces SECOND = 0.
     */
    @Column(name = "scheduled_local_time", nullable = false)
    private LocalDateTime scheduledLocalTime;

    /**
     * Lifecycle status of this occurrence.
     * <ul>
     *   <li>{@code due} — projected (default; stored row in this status = local-store only).</li>
     *   <li>{@code done} — terminal user action (pushed via {@code sync/push}).</li>
     *   <li>{@code snoozed} — terminal user action (pushed; {@link #snoozedUntil} is set).</li>
     *   <li>{@code missed} — derived on-device, end-of-local-day; NOT pushed in MVP (W-A).</li>
     * </ul>
     * DB CHECK enforces the four values.  M1 precedence is enforced at the apply path.
     */
    @Column(nullable = false)
    private String status;

    /**
     * Absolute-UTC instant of the {@code done}/{@code snoozed} user action.
     * {@code null} for {@code due}/{@code missed} rows.
     */
    @Column(name = "acted_at")
    private Instant actedAt;

    /**
     * Absolute-UTC re-fire instant for {@code snoozed} status.
     * {@code null} unless {@code status = snoozed}.
     */
    @Column(name = "snoozed_until")
    private Instant snoozedUntil;

    // -------------------------------------------------------------------------
    // <sync> block (data-model §1.2 / §2)
    // -------------------------------------------------------------------------

    /**
     * Server-assigned optimistic-concurrency token.  {@link Long} wrapper for Spring Data
     * JPA's {@code isNew()} detection (null before first persist).
     */
    @Version
    private Long version;

    /** Server-assigned on first INSERT; never changed after. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Server-assigned on EVERY apply.  The sole LWW merge clock. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone.  {@code null} = live.  {@code non-null} = tombstoned.
     * Tombstone-wins unconditionally (sync spec §A.5).
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Originating device UUID.  LWW tie-break only (sync spec §A.6). */
    @Column(name = "client_id")
    private UUID clientId;

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

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

    public UUID getReminderId() { return reminderId; }
    public void setReminderId(UUID reminderId) { this.reminderId = reminderId; }

    public LocalDateTime getScheduledLocalTime() { return scheduledLocalTime; }
    public void setScheduledLocalTime(LocalDateTime scheduledLocalTime) {
        this.scheduledLocalTime = scheduledLocalTime;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getActedAt() { return actedAt; }
    public void setActedAt(Instant actedAt) { this.actedAt = actedAt; }

    public Instant getSnoozedUntil() { return snoozedUntil; }
    public void setSnoozedUntil(Instant snoozedUntil) { this.snoozedUntil = snoozedUntil; }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
}
