package com.momstarter.consumption;

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
 * ConsumptionMapping — per-user activity→supply linking table for auto-stock-decrement.
 *
 * <p>Data-model §3.14 / auto-stock-decrement-architecture §4 / V20260710000023.
 *
 * <h3>DOMAIN BOUNDARY — HEALTH-SIDE ENTITY (INV-ASD-9)</h3>
 * <p>This is a HEALTH-SIDE entity, NOT a supplies-collection record:
 * <ul>
 *   <li>{@code feeding_formula} row reveals the mother formula-feeds + which item she uses
 *       → SD-10 sensitive health data; dual-gated {@code infant_feeding + general_health}.</li>
 *   <li>{@code diaper_change} / {@code bathing} rows are health-adjacent
 *       → {@code general_health} gate.</li>
 * </ul>
 * Storing this entity under {@code cloud_storage}-only is FORBIDDEN (INV-ASD-9): that would
 * expose "this mother formula-feeds + this specific item" to the supplies tier without the
 * SD-10 consent gate (PDPA ม.26/27 violation).
 *
 * <h3>supply_item_id is a SOFT REFERENCE — NO FK</h3>
 * <p>{@link #supplyItemId} references a supply_items row but carries NO database FK constraint
 * and NO {@code @JoinColumn} / {@code @ManyToOne}. Rationale: health-side and supply-side are
 * separate domains. Deleting a supply_item does NOT cascade to consumption_mapping.
 *
 * <h3>Mutable LWW record</h3>
 * <p>Same record-class as SupplyItem / MedicationPlan. Reconciled by LWW on server
 * {@code updated_at} + optimistic {@code @Version}.
 *
 * <h3>id is CLIENT-GENERATED</h3>
 * <p>UUIDv4, NOT server-minted. Spring Data JPA's {@code isNew()} uses the {@link Long}
 * wrapper {@link #version} (null before first persist).
 *
 * <h3>No crypto-shred (no *_cipher columns)</h3>
 * <p>All fields are plaintext (integers / booleans / enum strings). No per-row crypto-shred
 * sub-step needed on tombstone. However, rows contain health correlate data and MUST be in
 * TIER1_CHILD_DELETE_ORDER (AccountErasureService) and the 180-day tombstone GC list.
 */
@Entity
@Table(name = "consumption_mapping")
public class ConsumptionMapping {

    /**
     * Client-generated UUIDv4. No {@code @GeneratedValue}.
     * Spring Data JPA's {@code isNew()} uses {@link #version} (null before first persist).
     */
    @Id
    private UUID id;

    /** Owner user. FK → {@code users(id)}. NOT NULL. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Which health activity type drives the decrement.
     * DB CHECK constrains to {@code feeding_formula | diaper_change | bathing}.
     *
     * <p>Consent gate per activityType (INV-ASD-9):
     * <ul>
     *   <li>{@code feeding_formula} → {@code infant_feeding} + {@code general_health} (dual).</li>
     *   <li>{@code diaper_change} / {@code bathing} → {@code general_health} only.</li>
     * </ul>
     */
    @Column(name = "activity_type", nullable = false)
    private String activityType;

    /**
     * The supply_items row decremented by this activity.
     *
     * <p><strong>SOFT REFERENCE — NO DATABASE FK, NO ON DELETE CASCADE, NO {@code @JoinColumn}.</strong>
     * NULL is allowed (a mapping row may temporarily reference a deleted item;
     * the mobile trigger skips enabled=true rows whose referenced item is gone).
     */
    @Column(name = "supply_item_id")
    private UUID supplyItemId;

    /**
     * Per-use decrement amount in the linked item's sub-unit.
     * 0 = a no-op mapping (linked but draws nothing — edge case, not rejected).
     * DB CHECK (default_qty >= 0). NOT NULL.
     */
    @Column(name = "default_qty", nullable = false)
    private int defaultQty = 0;

    /**
     * Whether this mapping is currently active for auto-decrement.
     * TRUE = the on-device trigger uses this row. FALSE = preserved but inactive.
     * NOT NULL, DEFAULT TRUE.
     */
    @Column(nullable = false)
    private boolean enabled = true;

    // -------------------------------------------------------------------------
    // <sync> block
    // -------------------------------------------------------------------------

    /**
     * Server-assigned optimistic-concurrency token. {@link Long} wrapper (not primitive)
     * so Spring Data JPA can detect new entities via {@code version == null}.
     */
    @Version
    private Long version;

    /** Server-assigned on first INSERT; never changed after. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Server-assigned on EVERY apply. The sole LWW merge clock. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone. NULL = live. NOT NULL = tombstoned.
     * Tombstone-wins unconditionally. 180-day GC per database-schema §4.4.
     * No *_cipher columns → no crypto-shred sub-step on tombstone;
     * the ROWS themselves are purged by GC and TIER1 hard-delete.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Originating device UUID. LWW tie-break only (sync spec §A.6).
     * Nullable; absent = unknown device.
     */
    @Column(name = "client_id")
    private UUID clientId;

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
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

    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }

    public UUID getSupplyItemId() { return supplyItemId; }
    public void setSupplyItemId(UUID supplyItemId) { this.supplyItemId = supplyItemId; }

    public int getDefaultQty() { return defaultQty; }
    public void setDefaultQty(int defaultQty) { this.defaultQty = defaultQty; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
}
