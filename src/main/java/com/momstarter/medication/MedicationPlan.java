package com.momstarter.medication;

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
import java.util.UUID;

/**
 * MedicationPlan — a mutable drug/supplement schedule record (SD-2 health data, Slice 2).
 *
 * <p><strong>MUTABLE-LWW pattern</strong> — mirrors {@link com.momstarter.expense.Expense}
 * and {@code SupplyItem} / {@code ChecklistItem}. Name/dose/schedule/active fields are edited
 * in place; concurrent same-id edits converge by LWW on server {@code updated_at} + optimistic
 * {@code version}. Tombstone-wins unconditionally on soft-delete.
 *
 * <h3>id is CLIENT-GENERATED (NOT server-minted)</h3>
 * <p>The client supplies a UUIDv4 before pushing. No {@code @GeneratedValue}.
 * Spring Data JPA's {@code isNew()} uses the {@link Long} wrapper {@link #version}
 * (null before first persist). Re-push of the same id at the same version is the LWW
 * arbitration path; an outdated {@code version} returns {@code conflicts[]}.
 *
 * <h3>Encryption posture — ADR Option A (RULING 1)</h3>
 * <p>{@link #nameCipher} and {@link #doseCipher} are {@code bytea} columns.
 * MVP: hold PLAINTEXT bytes today (no-op/passthrough cipher — KMS + per-account DEK +
 * AES-GCM in a real EAS build is BLOCKED, HANDOFF §3). Real AES-GCM lands in THE SAME
 * COLUMNS at the KMS/EAS milestone with ZERO schema change. Server NEVER parses these
 * columns (INV-M3 / G4).
 *
 * <h3>scheduleRule — jsonb via @JdbcTypeCode (H2 masking guard)</h3>
 * <p>{@link #scheduleRule} is stored as {@code jsonb} in PostgreSQL and holds the
 * FLAG-4 recurrence grammar (RULING 7.1). Without
 * {@code @JdbcTypeCode(SqlTypes.JSON)}, Hibernate 6 sends the value as
 * {@code character varying}, which PostgreSQL rejects:
 * <pre>ERROR: column "schedule_rule" is of type jsonb but expression is of type
 *        character varying</pre>
 * H2 in PostgreSQL MODE silently accepts varchar into a jsonb column, so tests pass on
 * H2 but fail on real PostgreSQL — the classic h2-masks-jsonb-binding pattern.
 * This annotation is profile-independent and does NOT require {@code ?stringtype=unspecified}
 * in the JDBC URL. A full jsonb round-trip smoke belongs in Task 5's PgSmokeTest.
 *
 * <h3>ck_medication_plan__live_name CHECK constraint</h3>
 * <p>DB CHECK {@code (deleted_at IS NOT NULL OR name_cipher IS NOT NULL)} ensures
 * every live plan has a name. A tombstone may have {@link #nameCipher} crypto-shredded
 * to {@code null} (§4.4(A)). {@link #doseCipher} is genuinely optional (nullable, no guard).
 *
 * <h3>source_suggestion_state_id — SOFT LINK (no FK) (RULING 2)</h3>
 * <p>The {@code user_suggestion_state} table does NOT exist server-side in MVP.
 * {@link #sourceSuggestionStateId} is stored as an opaque {@code uuid} with no FK constraint.
 * Apply-path ownership validation is deferred/additive (active if/when the server table lands).
 *
 * <h3>Crypto-shred on tombstone (§4.4(A) / PDPA ruling 5a)</h3>
 * <p>On tombstone (when {@link #deletedAt} is written), the apply path MUST set
 * {@link #nameCipher} and {@link #doseCipher} to {@code null}.
 *
 * <h3>Tier-1 erasure (RULING 4)</h3>
 * <p>Registered in {@link com.momstarter.account.AccountErasureService#TIER1_CHILD_DELETE_ORDER}
 * AFTER {@code medication_log} (which references this table via a hard FK) and BEFORE {@code users}.
 * This FK order is critical: deleting {@code medication_plan} before {@code medication_log} raises
 * {@code DataIntegrityViolationException} and breaks account erasure.</p>
 *
 * <h3>Tombstone GC</h3>
 * <p>Registered in {@link com.momstarter.sync.TombstoneGcService#PURGE_TABLES}.
 * Tombstoned plans are hard-purged after the 180-day tombstone TTL (PDPA ม.33 / §4.4).</p>
 *
 * <h3>PDPA ม.30/31 export</h3>
 * <p>Included in the {@code GET /account/export} response via {@code AccountExportService}.
 * {@code nameCipher}, {@code doseCipher}, and {@code scheduleRule} are all exported.
 * Tombstoned rows are included (pre-GC window — user's right to access covers all records).</p>
 */
@Entity
@Table(name = "medication_plan")
public class MedicationPlan {

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
     * Drug/supplement name — {@code bytea} ciphertext (SD-2, Option A).
     * MVP: PLAINTEXT bytes (no-op cipher). Real AES-GCM at KMS/EAS milestone — same column.
     * Nullable (tombstone may have it crypto-shredded to null).
     * Live rows MUST carry a non-null value — guarded by {@code ck_medication_plan__live_name}.
     * Server NEVER parses or queries this value (INV-M3).
     */
    @Column(name = "name_cipher")
    private byte[] nameCipher;

    /**
     * Dose string — {@code bytea} ciphertext (SD-2, Option A). Genuinely optional;
     * nullable, no live-guard CHECK. Crypto-shredded to null on tombstone (§4.4(A)).
     * Server NEVER parses or queries this value (INV-M3).
     */
    @Column(name = "dose_cipher")
    private byte[] doseCipher;

    /**
     * FLAG-4 recurrence grammar stored as JSON ({@code jsonb} in PostgreSQL).
     * Nullable — a plan need not have a schedule (PRN / ad-hoc → {@code null}).
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} instructs Hibernate 6 to bind this field
     * using the JSON JDBC type rather than {@code character varying}. Without this
     * annotation PostgreSQL rejects the INSERT/UPDATE with:
     * <pre>ERROR: column "schedule_rule" is of type jsonb but expression is of type
     *        character varying</pre>
     * H2 in PostgreSQL MODE is lenient and accepts plain varchar into a jsonb column,
     * so tests pass on H2 but fail on real PostgreSQL — the classic h2-masks-jsonb-binding
     * BLOCKER pattern (memory: h2-masks-jsonb-binding). This annotation is profile-independent.
     *
     * <p>Shape: FLAG-4 grammar with civil anchor {@code startAt} folded in (RULING 7.1):
     * {@code {freq, startAt, interval?, timesOfDay[]?, until?}}. Validated on push
     * ({@code schedule_rule_invalid} sub-code — RULING 3). {@code null} is legal (PRN plan,
     * M=0 for adherence). Server validates grammar only — never expands for adherence (INV-M3;
     * adherence is computed CLIENT-SIDE by the on-device PDF assembler, RULING 7.3).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schedule_rule")
    private String scheduleRule;

    /**
     * Whether this plan is an active reminder source. Plaintext boolean (non-sensitive).
     * Default {@code true} (mirrors {@code DB DEFAULT true}).
     * A single LWW boolean — not time-versioned. {@code false} does NOT bound adherence
     * (RULING 7.2); only {@code deleted_at} removes the plan from the scored set.
     */
    @Column(nullable = false)
    private boolean active = true;

    /**
     * Soft provenance link to the suggestion that spawned this plan.
     * SOFT LINK — no DB FK (RULING 2): {@code user_suggestion_state} does NOT exist
     * server-side in MVP. Stored as opaque {@code uuid}; {@code null} for user-created plans.
     * Apply-path ownership check is deferred/additive (activates if/when the table lands).
     * Mirrors {@code checklist_item.source_suggestion_state_id} ({@code V20260630000009}).
     */
    @Column(name = "source_suggestion_state_id")
    private UUID sourceSuggestionStateId;

    // -------------------------------------------------------------------------
    // <sync> block (data-model §1.2 / §2 / database-schema §0.3)
    // -------------------------------------------------------------------------

    /**
     * Server-assigned optimistic-concurrency token.
     * {@link Long} wrapper for Spring Data JPA's {@code isNew()} detection (null before first persist).
     * LWW: bumped on every successful UPDATE. {@code version == 0} after fresh INSERT;
     * {@link MedicationPlanRepository#initVersionToOne} immediately bumps to 1.
     */
    @Version
    private Long version;

    /** Server-assigned on first INSERT; never changed after. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Server-assigned on EVERY apply. The sole LWW merge clock.
     * {@link PreUpdate} overwrites whatever the client sent with the server's current clock.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone. {@code null} = live; {@code non-null} = tombstoned.
     * Tombstone-wins unconditionally (sync spec). On tombstone: crypto-shred
     * {@link #nameCipher} and {@link #doseCipher} to {@code null} (§4.4(A)).
     * GC after {@code TOMBSTONE_TTL} (180 days).
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Originating device UUID. LWW tie-break only (sync spec §A.6).
     * Nullable; absent = unknown device. No FK.
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
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public byte[] getNameCipher() { return nameCipher; }
    public void setNameCipher(byte[] nameCipher) { this.nameCipher = nameCipher; }

    public byte[] getDoseCipher() { return doseCipher; }
    public void setDoseCipher(byte[] doseCipher) { this.doseCipher = doseCipher; }

    public String getScheduleRule() { return scheduleRule; }
    public void setScheduleRule(String scheduleRule) { this.scheduleRule = scheduleRule; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public UUID getSourceSuggestionStateId() { return sourceSuggestionStateId; }
    public void setSourceSuggestionStateId(UUID sourceSuggestionStateId) {
        this.sourceSuggestionStateId = sourceSuggestionStateId;
    }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
}
