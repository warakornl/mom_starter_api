package com.momstarter.supply;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * SupplyItem — a household/convenience supply the mother tracks on-hand (data-model §3.9).
 *
 * <p>The <strong>first entity wired end-to-end</strong> with the offline-sync engine
 * (OQ-SYNC-18). Mutable record, reconciled by <strong>LWW on server {@code updated_at}</strong>
 * + optimistic {@code version}, exactly like {@code MedicationPlan}/{@code ChecklistItem}
 * (data-model §3.9 / offline-sync §C).
 *
 * <h3>id is CLIENT-GENERATED (NOT server-minted)</h3>
 * <p>The client supplies a UUIDv4 before pushing. The server MUST NOT generate or replace it —
 * offline creates are globally unique by client UUID, and a re-sent push is idempotent on the
 * same {@code id} (sync spec §A.4 / data-model §2). Consequently:
 * <ul>
 *   <li>There is <strong>no {@code @GeneratedValue}</strong> on {@code id}.</li>
 *   <li>{@link #setId} is provided and MUST be called before {@link #save}.</li>
 *   <li>Spring Data JPA's {@code isNew()} detects a new entity via
 *       {@code version == null} (using the {@code Long} wrapper — see field below).</li>
 * </ul>
 *
 * <h3>NON-health — no field-level encryption</h3>
 * <p>{@code supply_item} is light personal data (standard KMS-at-rest, no {@code *_cipher}
 * columns, no new consent type). Gated by {@code cloud_storage} consent only — CONFIRMED by
 * {@code security-compliance} (data-model §3.9). The category {@code health-supplies} is a
 * grouping label for consumables, NOT a health record.
 *
 * <h3>updated_at / version stamping</h3>
 * <p>JPA {@link Version} + {@link PrePersist}/{@link PreUpdate} provide app-level server-clock
 * authority, consistent with the existing codebase pattern (see {@code PregnancyProfile}).
 * The DB {@code DEFAULT now()} / {@code DEFAULT 0} are safety nets for out-of-band inserts only.
 */
@Entity
@Table(name = "supply_items")
public class SupplyItem {

    /**
     * Client-generated UUIDv4. Set by the client before push; the server preserves it.
     * No {@code @GeneratedValue} — this must never be replaced by the server.
     *
     * <p>Spring Data JPA's {@code isNew()} check uses {@link #version} ({@code Long} wrapper,
     * {@code null} before first persist) rather than the id to detect new vs. existing entities.
     * This is required because the id is set by the client (never {@code null} at save time).
     */
    @Id
    private UUID id;

    /**
     * Owner user. FK → {@code users(id)}. NOT NULL.
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Free-text item name, language-agnostic, stored verbatim, NEVER parsed.
     * NON-health → plaintext (no {@code _cipher} column). security-compliance CONFIRMED.
     */
    @Column(nullable = false)
    private String name;

    /**
     * Fixed enum (data-model §3.9 / offline-sync §C.2):
     * {@code diapers | feeding | hygiene | health-supplies | other}.
     * The DB CHECK constraint is the enforcement layer.
     */
    @Column(nullable = false)
    private String category;

    /**
     * Free-text unit label (e.g. "pcs", "pack", "tin"). Nullable, non-sensitive.
     */
    private String unit;

    /**
     * Current on-hand quantity. Mutable. Clamped at 0, NEVER negative.
     * The sync apply path clamps before persist; the DB {@code CHECK (on_hand_qty >= 0)}
     * is the backstop (offline-sync §A.7 / E10).
     */
    @Column(name = "on_hand_qty", nullable = false)
    private int onHandQty = 0;

    /**
     * Low-supply alert threshold. Nullable — {@code null} means the item never raises
     * a low-supply alert (data-model §3.9).
     */
    @Column(name = "low_threshold")
    private Integer lowThreshold;

    /**
     * Cross-device de-nag marker for the low-supply alert (data-model §3.9 / offline-sync §C.2).
     * <ul>
     *   <li>{@code null}  ⇒ no outstanding low-notification for the current low-episode.</li>
     *   <li>NOT-NULL ⇒ this low-episode has already been alerted (stored int = {@code version}
     *       at alert-time — an ABA/ordering guard; the server treats it as an ordinary LWW field
     *       and does NOT recompute or validate it, contrast {@code reminder_occurrence.id}).</li>
     * </ul>
     * No MVP logic reads the stored int's value; suppression keys ONLY off NULL vs NOT-NULL.
     */
    @Column(name = "low_notified_at_version")
    private Integer lowNotifiedAtVersion;

    // -------------------------------------------------------------------------
    // <sync> block (data-model §1.2 / §2 / database-schema §0.3)
    // -------------------------------------------------------------------------

    /**
     * Optimistic-concurrency token (data-model §2 / sync spec §0.1).
     * <strong>Server-assigned, monotonic.</strong> The client never writes this; it only
     * echoes back the last-pulled value as its <em>base version</em> on push.
     *
     * <p>Using {@code Long} (wrapper, not primitive) is intentional: Spring Data JPA's
     * {@code isNew()} detects a new entity when {@code version == null}. Because the
     * {@code id} is client-supplied (never {@code null} before {@code save()}), the
     * id-null check would misclassify every new entity as existing; the null-version check
     * avoids the extra {@code SELECT} and incorrect {@code merge()} path that would follow.
     * Hibernate sets {@code version = 0L} on the first INSERT; {@code Long} becomes {@code 0},
     * which satisfies the DB {@code NOT NULL DEFAULT 0} constraint.
     */
    @Version
    private Long version;

    /** Server-assigned on first insert; never changed after. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Server-assigned on EVERY apply. The sole LWW merge clock for multi-device ordering
     * (data-model §2 / sync spec §0.1). The client value MUST NOT define merge order;
     * {@link PreUpdate} overwrites whatever the client sent with the server's current clock.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone. {@code null} = live record; {@code non-null} = tombstoned.
     * Tombstone-wins unconditionally (sync spec §A.5). The tombstone propagates via
     * {@code sync/pull} so other devices observe the deletion (180-day GC, database-schema §4.4).
     * No {@code *_cipher} columns exist here, so the crypto-shred sub-step on soft-delete
     * is a no-op for this table.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Originating device UUID. LWW tie-break only (data-model §2 / sync spec §A.6).
     * Nullable; absent = unknown device.
     */
    @Column(name = "client_id")
    private UUID clientId;

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    /**
     * Sets server-side timestamps on first persist. Does NOT generate {@code id} — the id
     * MUST already be set by the client. Does NOT set {@code version} — Hibernate's
     * {@code @Version} mechanism handles that (initial value = 0L on INSERT).
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
     * over the LWW merge order (sync spec §0.1, hard invariant 1).
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    /** Called by the client (or sync apply path) to set the client-generated UUID before save. */
    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public int getOnHandQty() {
        return onHandQty;
    }

    public void setOnHandQty(int onHandQty) {
        this.onHandQty = onHandQty;
    }

    public Integer getLowThreshold() {
        return lowThreshold;
    }

    public void setLowThreshold(Integer lowThreshold) {
        this.lowThreshold = lowThreshold;
    }

    public Integer getLowNotifiedAtVersion() {
        return lowNotifiedAtVersion;
    }

    public void setLowNotifiedAtVersion(Integer lowNotifiedAtVersion) {
        this.lowNotifiedAtVersion = lowNotifiedAtVersion;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public UUID getClientId() {
        return clientId;
    }

    public void setClientId(UUID clientId) {
        this.clientId = clientId;
    }
}
