package com.momstarter.feeding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * FeedingSession — breast / pump / formula feed log.
 *
 * <p>Data classification: SD-10 ({@code infant_feeding}, ม.20 parental + ม.26 sensitive health).
 * Data-model §3.14 / auto-stock-decrement-architecture §2 / V20260710000020.
 *
 * <h3>id is CLIENT-GENERATED</h3>
 * <p>UUIDv4 from the client — NOT server-minted. No {@code @GeneratedValue}.
 * Spring Data JPA's {@code isNew()} uses the {@link Long} wrapper {@link #version}
 * (null before first persist) to detect new vs. existing entities.
 *
 * <h3>Immutable-event union (create-only)</h3>
 * <p>Each row is a distinct UUID; once pushed the row is NEVER overwritten.
 * Re-push of the same id is an idempotent no-op (server echoes current
 * version/updatedAt). Only tombstone (applyDelete) may change a persisted row.
 * Mirrors the {@code KickCountSession} / {@code SelfLog} / {@code MedicationLog} pattern.
 *
 * <h3>Floating-civil started_at (FLAG-1)</h3>
 * <p>{@link #startedAt} is stored as {@code timestamp WITHOUT TIME ZONE} — never UTC-normalised.
 * It is the civil calendar bucket key for daily grouping in history and the PDF.
 *
 * <h3>kind=formula + amountSubUnits (ASD §2 / §3.14)</h3>
 * <p>{@link #amountSubUnits} is the per-formula-feed scoop/serving count used by the
 * on-device auto-decrement trigger. It is meaningful ONLY when {@link #kind} = {@code formula};
 * the DB enforces {@code CHECK (kind = 'formula' OR amount_sub_units IS NULL)}.
 * The server stores and echoes this value VERBATIM; it NEVER parses, aggregates, or uses it
 * for any server-side computation (G4 / INV-ASD-4).
 *
 * <h3>INV-ASD-4 / INV-ASD-8 / INV-ASD-9 — ZERO supply-side linkage</h3>
 * <p>This entity carries NO {@code supply_item_id}, NO {@code fed_at}, NO per-feed FK,
 * NO {@code uses_remaining_in_open_container} column. Zero-linkage between the health
 * side and the supply side is enforced by construction (column absence from this schema).
 *
 * <h3>note_cipher crypto-shred on tombstone</h3>
 * <p>When the row is tombstoned ({@link #deletedAt} set), {@link #noteCipher} is set to
 * {@code null} in the same UPDATE (PDPA ม.33 / pdpa-assessment ruling 5 / V20260710000020).
 *
 * <h3>Consent gate (dual)</h3>
 * <p>Push requires BOTH {@code general_health} AND {@code infant_feeding}.
 * Handled at collection level by {@code FeedingSessionSyncCollection} +
 * {@code SyncCollection.additionalCollectionConsentTypes()}.
 */
@Entity
@Table(name = "feeding_session")
public class FeedingSession {

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
     * Feed modality.
     * DB CHECK constrains to {@code breastfeed | pump | formula}.
     * {@code formula} is the ASD trigger kind (§3.14 / ASD §2).
     */
    @Column(nullable = false)
    private String kind;

    /**
     * Which breast was used. Constrained to {@code left | right | both}.
     * Nullable — not applicable for pump/formula.
     */
    @Column
    private String side;

    /**
     * Feed start — floating-civil calendar bucket key (FLAG-1).
     * {@code timestamp WITHOUT TIME ZONE} — never UTC-normalised.
     * Minute-precision: {@code YYYY-MM-DDTHH:mm}.
     */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /**
     * Elapsed feed time in whole seconds. Optional (nullable).
     * CLIENT-COMPUTED, stored VERBATIM — server NEVER recomputes (DRIFT-1).
     */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /**
     * Expressed/formula volume in millilitres. Optional (nullable).
     * Exact numeric — never float (no rounding drift, G4).
     */
    @Column(name = "volume_ml", precision = 10, scale = 2)
    private BigDecimal volumeMl;

    /**
     * Per-formula-feed scoop/serving count in the linked item's sub-unit.
     * MEANINGFUL ONLY for {@code kind=formula}; MUST be NULL for breastfeed/pump.
     * DB enforces {@code CHECK (kind='formula' OR amount_sub_units IS NULL)}.
     * 0 = a logged feed with zero scoops (no-op use, allowed).
     * Server stores VERBATIM; NEVER parses or aggregates (G4 / INV-ASD-4).
     * SD-10 health-side value; NEVER copied to the supplies collection (INV-ASD-4).
     */
    @Column(name = "amount_sub_units")
    private Integer amountSubUnits;

    /**
     * Optional client-encrypted free-text note (AES-GCM / per-account DEK). Bytea.
     * NEVER parsed by the server (INV-K1 principle applied here too).
     * Crypto-shred: set to {@code null} when {@link #deletedAt} is written.
     */
    @Column(name = "note_cipher")
    private byte[] noteCipher;

    // -------------------------------------------------------------------------
    // <sync> block
    // -------------------------------------------------------------------------

    /**
     * Server-assigned optimistic-concurrency token. {@link Long} wrapper (not primitive)
     * so Spring Data JPA can detect new entities via {@code version == null}.
     * For immutable events: version:=1 on INSERT; bumped only on tombstone.
     */
    @Version
    private Long version;

    /** Server-assigned on first INSERT; never changed after. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Server-assigned on EVERY apply. LWW merge clock authority. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone. {@code null} = live. {@code non-null} = tombstoned.
     * On tombstone: {@link #noteCipher} MUST be set to {@code null} (crypto-shred).
     * 180-day GC (TOMBSTONE_TTL) per database-schema §4.4.
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

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public BigDecimal getVolumeMl() { return volumeMl; }
    public void setVolumeMl(BigDecimal volumeMl) { this.volumeMl = volumeMl; }

    public Integer getAmountSubUnits() { return amountSubUnits; }
    public void setAmountSubUnits(Integer amountSubUnits) { this.amountSubUnits = amountSubUnits; }

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
