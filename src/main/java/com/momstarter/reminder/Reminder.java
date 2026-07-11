package com.momstarter.reminder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Reminder — alarm-style reminder config synced to every device (data-model §3.5, US-3/4).
 *
 * <p>MOTHER-health collection; gated by {@code general_health} (per-collection) +
 * {@code cloud_storage} (whole-batch) on {@code sync/push} / {@code sync/pull}.
 *
 * <h3>Sync pattern</h3>
 * <p>Mutable record → LWW on server {@code updated_at} + optimistic {@code version},
 * identical to {@link com.momstarter.supply.SupplyItem}.
 * All mutations flow through {@code POST /sync/push} (collection {@code reminders}).
 * The REST {@code GET /reminders} is read-only (restore/verification view).
 *
 * <h3>id is CLIENT-GENERATED (NOT server-minted)</h3>
 * <p>The client supplies a UUIDv4 before pushing. No {@code @GeneratedValue} — the server
 * preserves the client UUID so offline creates are globally unique and push is idempotent
 * (sync spec §A.4 / data-model §2). Spring Data JPA's {@code isNew()} uses the
 * {@link Long} wrapper {@link #version} ({@code null} before first persist) — NOT the id.
 *
 * <h3>Recurrence grammar (FLAG-4)</h3>
 * <p>{@link #recurrenceRule} stores the constrained JSON grammar:
 * {@code {freq, interval?, timesOfDay[]?, until?}} — validated on {@code sync/push} (422
 * on malformed grammar). Stored as {@code jsonb} in PostgreSQL; a {@code String} here.
 *
 * <h3>Floating-civil start_at (FLAG-1)</h3>
 * <p>{@link #startAt} is a floating civil wall-clock anchor for deterministic expansion
 * (no timezone, no UTC normalization). {@link LocalDateTime} maps to
 * {@code timestamp WITHOUT TIME ZONE}.
 *
 * <h3>Soft link to source entity</h3>
 * <p>{@link #sourceRefType} + {@link #sourceRefId} are an optional soft link — NO FK.
 * The client cancels a linked Reminder by pushing two tombstones: one for the referencing
 * entity and one for this Reminder. See data-model §3.9 / §5.
 */
@Entity
@Table(name = "reminders")
public class Reminder {

    /**
     * Client-generated UUIDv4.  No {@code @GeneratedValue}.
     * Spring Data JPA's {@code isNew()} uses {@link #version} (null before first persist).
     */
    @Id
    private UUID id;

    /** Owner user. FK → {@code users(id)}. NOT NULL. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Alarm category.  Constrained by DB CHECK to
     * {@code medication | kick_count | feeding | appointment | supply_restock | custom}.
     */
    @Column(nullable = false)
    private String type;

    /**
     * Non-sensitive display label (lock-screen-safe, SD-11).  Required.
     * Language-agnostic; stored verbatim; never parsed.
     */
    @Column(name = "display_title", nullable = false)
    private String displayTitle;

    /**
     * Type of the optional linked entity.
     * Constrained by DB CHECK to {@code medication_plan | checklist_item | supply_item}.
     * {@code null} when there is no source link.
     */
    @Column(name = "source_ref_type")
    private String sourceRefType;

    /**
     * UUID of the optional linked entity.  Soft link — NO FK.
     * {@code null} when there is no source link.
     * The client cancels this Reminder (and its source) by pushing two tombstones over
     * {@code sync/push}.
     */
    @Column(name = "source_ref_id")
    private UUID sourceRefId;

    /**
     * FLAG-4 recurrence grammar stored as JSON (jsonb in PostgreSQL).
     * Validated on {@code sync/push} (422) against the grammar schema.
     * Fields: {@code freq} ({@code one_off|daily|every_n_days}), {@code interval?},
     * {@code timesOfDay[]?}, {@code until?}.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} instructs Hibernate 6 to bind this field
     * using the JSON JDBC type rather than {@code character varying}. Without this
     * annotation PostgreSQL rejects the INSERT/UPDATE with:
     * <pre>ERROR: column "recurrence_rule" is of type jsonb but expression is of type
     *        character varying</pre>
     * H2 in PostgreSQL MODE is lenient and accepts plain varchar into a jsonb column,
     * so tests pass on H2 but fail on real PostgreSQL — the classic persistence-drift
     * BLOCKER-1 pattern. This annotation is profile-independent and requires no
     * {@code ?stringtype=unspecified} in the JDBC URL.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recurrence_rule", nullable = false)
    private String recurrenceRule;

    /**
     * Floating-civil recurrence anchor (FLAG-1 / FLAG-4).
     * {@link LocalDateTime} → {@code timestamp WITHOUT TIME ZONE} — never UTC-normalized.
     * Format: {@code YYYY-MM-DDTHH:mm} (minute precision, no zone/offset).
     * The same civil string is passed to the deterministic uuidv5 expansion algorithm on
     * both the server and every device so projected and materialized occurrence instances
     * share the same {@code scheduledLocalCivil} → the same occurrence id.
     */
    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    /**
     * {@code true} → the device schedules local alarms from this Reminder definition.
     * {@code false} → definition preserved but no new alarms (e.g. paused medication plan).
     */
    @Column(nullable = false)
    private boolean active = true;

    /**
     * By-ITEM pregnancy-loss survival allow-list flag (AC-2.3 / LOSS-INV-5).
     * Default {@code false} = "pregnancy-progress, deactivate on loss" — the safe default
     * (Z-18: fail toward stopping pregnancy content). {@code true} = this reminder is NOT
     * pregnancy-progress and MUST keep firing across a loss (e.g. a post-loss clinical
     * follow-up appointment, or a non-pregnancy medication).
     *
     * <p><strong>Never inferred from {@link #type}</strong> — the precise clinical
     * membership (which reminder kinds/instances are pregnancy-progress vs surviving) needs
     * clinical + {@code security-compliance} sign-off and is not fixed by this column alone
     * (data-model §5 L282 / functional-spec §8).
     *
     * <p>Read by the {@code POST /pregnancy-profile/loss-event} sweep: every reminder with
     * {@code survivesEnded = false AND active = true AND deletedAt IS NULL} is deactivated in
     * the same DB transaction as the {@code lifecycle → 'ended'} write.
     */
    @Column(name = "survives_ended", nullable = false)
    private boolean survivesEnded = false;

    /**
     * Reversible-tombstone provenance marker (data-model §5 L512 / functional-spec §6).
     * {@code null} = never swept by the pregnancy-loss path. {@code "loss_event"} = the only
     * value this MVP writes — stamped by the {@code loss-event} sweep alongside
     * {@link #deactivatedAt}, cleared back to {@code null} by the {@code reopen} sweep.
     *
     * <p>This is the <strong>scoping key</strong> for {@code reopen}'s re-activation predicate:
     * only rows with {@code deactivatedBy = "loss_event"} (AND {@code deletedAt IS NULL}) are
     * re-activated — a reminder the user themselves soft-deleted while {@code ended} keeps its
     * tombstone and is excluded, even though this marker may still read {@code "loss_event"}
     * (functional-spec §6.2 / §10.7).
     *
     * <p><strong>Deactivate (ม.21) &ne; erase (ม.33)</strong> — this is a soft deactivation
     * marker, NOT a {@link #deletedAt} hard-delete and NOT a crypto-shred; the source reminder
     * row is fully retained (US-3/AC-3.3 export). No auto-purge on {@code lifecycle='ended'}
     * (S5) — this column is intentionally excluded from the 180-day tombstone-GC horizon,
     * which keys off {@link #deletedAt} only.
     */
    @Column(name = "deactivated_by")
    private String deactivatedBy;

    /**
     * Absolute-UTC instant the {@code loss_event} sweep deactivated this reminder (the
     * {@code <sync>} clock family, FLAG-1 — a system/action instant, NOT a floating-civil
     * bucket-key event time). {@code null} unless {@link #deactivatedBy} is set. Cleared back
     * to {@code null} by the {@code reopen} sweep in the same transaction as
     * {@link #deactivatedBy}.
     */
    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    /**
     * Optional tag marking this reminder as a care-activity reminder for auto-stock-decrement.
     *
     * <p>Constrained by DB CHECK to {@code 'diaper_change' | 'bathing'} (V20260710000022).
     * NULL = not a care-activity reminder (default; all existing reminders unaffected).
     *
     * <p>When a linked ReminderOccurrence transitions to {@code done}, the on-device
     * auto-decrement trigger reads this field, looks up enabled ConsumptionMapping rows
     * for the activity_type, and decrements the linked supply item(s) (ASD §1.1).
     *
     * <p>Gate: {@code general_health} (diaper_change and bathing are RoPA A17 activities).
     * NOTE: 'feeding_formula' is intentionally absent — formula feeding uses
     * {@code FeedingSession(kind=formula)}, NOT a Reminder (ASD §1.1 / INV-ASD-1).
     */
    @Column(name = "care_activity_type")
    private String careActivityType;

    // -------------------------------------------------------------------------
    // <sync> block (data-model §1.2 / §2 / database-schema §0.3)
    // -------------------------------------------------------------------------

    /**
     * Server-assigned optimistic-concurrency token. {@link Long} wrapper (not primitive)
     * so Spring Data JPA can detect new entities via {@code version == null} when the
     * client-supplied {@code id} is already non-null before save.
     */
    @Version
    private Long version;

    /** Server-assigned on first INSERT; never changed after. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Server-assigned on EVERY apply. The sole LWW merge clock (data-model §2 / sync spec §0.1).
     * {@link PreUpdate} overwrites any client value with the server's current clock.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone. {@code null} = live. {@code non-null} = tombstoned.
     * Tombstone-wins unconditionally (sync spec §A.5).
     * 180-day GC (TOMBSTONE_TTL) per database-schema §4.4.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Originating device UUID. LWW tie-break only (sync spec §A.6 / data-model §2).
     * Nullable; absent = unknown device.
     */
    @Column(name = "client_id")
    private UUID clientId;

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    /**
     * Sets server-side timestamps on first persist.
     * Does NOT generate {@code id} (the client must set it before save).
     * Does NOT set {@code version} (Hibernate's {@code @Version} sets 0L on INSERT).
     */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    /**
     * Stamps the server's current clock on every mutation, enforcing server-clock authority
     * over LWW merge order (sync spec §0.1, hard invariant 1).
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }

    /** Called by the sync apply path to set the client-generated UUID before save. */
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDisplayTitle() { return displayTitle; }
    public void setDisplayTitle(String displayTitle) { this.displayTitle = displayTitle; }

    public String getSourceRefType() { return sourceRefType; }
    public void setSourceRefType(String sourceRefType) { this.sourceRefType = sourceRefType; }

    public UUID getSourceRefId() { return sourceRefId; }
    public void setSourceRefId(UUID sourceRefId) { this.sourceRefId = sourceRefId; }

    public String getRecurrenceRule() { return recurrenceRule; }
    public void setRecurrenceRule(String recurrenceRule) { this.recurrenceRule = recurrenceRule; }

    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isSurvivesEnded() { return survivesEnded; }
    public void setSurvivesEnded(boolean survivesEnded) { this.survivesEnded = survivesEnded; }

    public String getDeactivatedBy() { return deactivatedBy; }
    public void setDeactivatedBy(String deactivatedBy) { this.deactivatedBy = deactivatedBy; }

    public Instant getDeactivatedAt() { return deactivatedAt; }
    public void setDeactivatedAt(Instant deactivatedAt) { this.deactivatedAt = deactivatedAt; }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }

    public String getCareActivityType() { return careActivityType; }
    public void setCareActivityType(String careActivityType) { this.careActivityType = careActivityType; }
}
