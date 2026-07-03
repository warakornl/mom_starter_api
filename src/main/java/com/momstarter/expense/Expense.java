package com.momstarter.expense;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Expense — a personal spending/budgeting entry (expenses-feature §0, expenses-ui.md).
 *
 * <p>Second non-health sync collection (after {@code supply_items}).
 * Mutable record reconciled by <strong>LWW on server {@code updated_at}</strong>
 * + optimistic {@code version}, exactly like {@code SupplyItem} and {@code ChecklistItem}.
 *
 * <h3>id is CLIENT-GENERATED (NOT server-minted)</h3>
 * <p>The client supplies a UUIDv4 before pushing. The server MUST NOT generate or replace it —
 * offline creates are globally unique by client UUID, and a re-sent push is idempotent on the
 * same {@code id} (sync spec §A.4 / data-model §2). Consequently:
 * <ul>
 *   <li>There is <strong>no {@code @GeneratedValue}</strong> on {@code id}.</li>
 *   <li>{@link #setId} is provided and MUST be called before save.</li>
 *   <li>Spring Data JPA's {@code isNew()} detects a new entity via
 *       {@code version == null} (using the {@code Long} wrapper — see {@link #version}).</li>
 * </ul>
 *
 * <h3>NON-health — no field-level encryption</h3>
 * <p>{@code expense} is non-health personal-financial data (standard KMS-at-rest,
 * no {@code *_cipher} columns, no new consent type). Gated by {@code cloud_storage} only.
 * The {@code healthcare} category is a SPENDING LABEL only, NOT a health record.
 *
 * <h3>ARCHITECTURE GAP (flagged for solution-architect)</h3>
 * <p>expenses-ui.md §8 (EX-1/EX-2) describes {@link #amount} and {@link #note} as
 * client-encrypted. This implementation stores both as plaintext under at-rest KMS volume
 * encryption, consistent with the task directive ("store as integer satang") and the
 * {@code supply_items} precedent. If field-level encryption is confirmed by the architect,
 * {@link #amount} must change to a {@code bytea} cipher column and the sync engine must
 * handle cipher bytes rather than typed values.
 *
 * <h3>amount — satang (minor units)</h3>
 * <p>{@code amount} is stored in satang (1 THB = 100 satang) as a non-negative integer.
 * This avoids floating-point drift (architect note §3.4). Example: ฿590 → 59000 satang.
 *
 * <h3>incurred_on — floating-civil date bucket key</h3>
 * <p>{@link #incurredOn} is a {@link LocalDate} ({@code date} column, no time component).
 * It is the civil calendar day the expense occurred — the bucket key for monthly totals.
 * NEVER UTC-normalised; stored exactly as the user entered it (expenses-ui.md §3.1).
 */
@Entity
@Table(name = "expenses")
public class Expense {

    /**
     * Client-generated UUIDv4. Set by the client before push; the server preserves it.
     * No {@code @GeneratedValue} — this must never be replaced by the server.
     *
     * <p>Spring Data JPA's {@code isNew()} uses {@link #version} ({@code Long} wrapper,
     * {@code null} before first persist) rather than the id to detect new vs. existing entities.
     */
    @Id
    private UUID id;

    /**
     * Owner user. FK → {@code users(id)}. NOT NULL.
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Spending amount in satang (Thai minor units; 1 baht = 100 satang). Non-negative integer.
     * DB CHECK (amount >= 0) is the backstop; the sync apply path validates before persisting.
     * Example: ฿590.00 → amount = 59000 satang.
     */
    @Column(nullable = false)
    private int amount;

    /**
     * Fixed 5-enum category (expenses-feature §3.2 / expenses-ui.md §3.3):
     * {@code baby-supplies | healthcare | baby-gear | mother | other}.
     * DB CHECK constraint is the enforcement layer.
     */
    @Column(nullable = false)
    private String category;

    /**
     * Floating-civil date bucket key (expenses-ui.md §3.1).
     * The civil calendar day on which the expense occurred.
     * Determines which month's total this expense counts toward.
     * NEVER UTC-normalised — stored exactly as the user entered it.
     * Mapped to DB type {@code date} (no time component).
     */
    @Column(name = "incurred_on", nullable = false)
    private LocalDate incurredOn;

    /**
     * Optional free-text note. Stored verbatim, NEVER parsed (EX-2 posture).
     * Language-agnostic; echoed back to the client only.
     */
    @Column
    private String note;

    // -------------------------------------------------------------------------
    // <sync> block (data-model §1.2 / §2 / database-schema §0.3)
    // -------------------------------------------------------------------------

    /**
     * Optimistic-concurrency token (data-model §2 / sync spec §0.1).
     * <strong>Server-assigned, monotonic.</strong> The client never writes this.
     *
     * <p>Using {@code Long} (wrapper, not primitive) so that Spring Data JPA's
     * {@code isNew()} detects a new entity when {@code version == null}.
     */
    @Version
    private Long version;

    /** Server-assigned on first insert; never changed after. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Server-assigned on EVERY apply. The sole LWW merge clock.
     * The client value MUST NOT define merge order; {@link PreUpdate} overwrites
     * whatever the client sent with the server's current clock.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone. {@code null} = live record; {@code non-null} = tombstoned.
     * Tombstone-wins unconditionally (sync spec §A.5).
     * 180-day GC window per database-schema §4.4 / TombstoneGcService.PURGE_TABLES.
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

    /**
     * Sets server-side timestamps on first persist. Does NOT generate {@code id}.
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

    public UUID getId() { return id; }

    /** Called by the client (or sync apply path) to set the client-generated UUID before save. */
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public LocalDate getIncurredOn() { return incurredOn; }
    public void setIncurredOn(LocalDate incurredOn) { this.incurredOn = incurredOn; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
}
