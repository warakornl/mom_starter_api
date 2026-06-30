package com.momstarter.kickcount;

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
 * KickCountSession — immutable event record for the Cardiff count-to-ten (นับลูกดิ้น) feature.
 * Data-model §3.13, US-K1…K8, SD-3.  Capture type (a): structured tracking module.
 * MOTHER-health collection.
 *
 * <h3>id is CLIENT-GENERATED (NOT server-minted)</h3>
 * <p>No {@code @GeneratedValue}. Spring Data JPA's {@code isNew()} uses the
 * {@link Long} wrapper {@link #version} (null before first persist).
 * See api-contract "Offline-sync engine §A.4 / data-model §2".
 *
 * <h3>Immutable event — create-only union (§4)</h3>
 * <p>Each session is a distinct UUID; re-push of the same id is an idempotent no-op
 * (the server echoes current version/updatedAt without overwriting fields).
 * Only {@code completed} sessions are ever persisted — the DB has
 * {@code CHECK (status = 'completed')} as a structural guard (data-model §3.13).
 *
 * <h3>Floating-civil timestamps (FLAG-1)</h3>
 * <p>{@link #startedAt} and {@link #endedAt} are stored as
 * {@code timestamp WITHOUT TIME ZONE} — they are civil wall-clock values (never UTC-normalised).
 * {@code started_at} is the calendar bucket key for daily grouping in history (US-K7) and PDF.
 *
 * <h3>Verbatim storage (DRIFT-1)</h3>
 * <p>{@link #durationSeconds} is CLIENT-COMPUTED and stored verbatim.
 * {@link #gestationalWeekAtStart} is a CLIENT-DERIVED snapshot.
 * The server NEVER recomputes either value from other fields.
 *
 * <h3>note_cipher (K-2 / K-7)</h3>
 * <p>Client-encrypted free-text note (AES-GCM / per-account DEK). Stored as {@code bytea},
 * NEVER parsed. PDF inclusion gate: {@code sensitive_lab_results} / {@code includeLab=true} (K-7).
 * Crypto-shred on tombstone: {@link #noteCipher} set to {@code null} when
 * {@link #deletedAt} is written (pdpa-assessment ruling 5a).
 *
 * <h3>PDPA retention (K-6)</h3>
 * <p>Follows the shared MOTHER-health retention window.
 * Tombstone GC after 180 days ({@code TOMBSTONE_TTL}).
 * Per-account DEK crypto-shred on {@code DELETE /account}.
 */
@Entity
@Table(name = "kick_count_session")
public class KickCountSession {

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
     * Session start — floating-civil calendar bucket key (FLAG-1).
     * {@code timestamp WITHOUT TIME ZONE} — never UTC-normalised.
     * Minute-precision as agreed in api-contract "KickCountSessionInput" / FLAG-1.
     */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /**
     * Session end — floating-civil wall-clock (FLAG-1).
     * Always set on a persisted (completed) row — required per api-contract B1.
     * Invariant: {@code endedAt ≥ startedAt} (enforced on the apply path).
     */
    @Column(name = "ended_at", nullable = false)
    private LocalDateTime endedAt;

    /**
     * Elapsed counting time in whole seconds.
     * CLIENT-COMPUTED and stored VERBATIM (DRIFT-1).
     * The server NEVER recomputes from {@link #startedAt}/{@link #endedAt}.
     */
    @Column(name = "duration_seconds", nullable = false)
    private Integer durationSeconds = 0;

    /**
     * Number of fetal movements tapped by the user.
     * Structured health value — PLAINTEXT-AT-REST under KMS volume encryption.
     * NEVER interpreted or thresholded (INV-K1/K2). Can be 0..N.
     */
    @Column(name = "movement_count", nullable = false)
    private Integer movementCount = 0;

    /**
     * Optional client-encrypted free-text note (AES-GCM / per-account DEK).
     * Stored as {@code bytea}. NEVER parsed. Echoed back to client only.
     * PDF gate: {@code sensitive_lab_results} opt-in ({@code includeLab=true}) — K-7.
     * Crypto-shred on tombstone (set to {@code null} when {@link #deletedAt} is written).
     */
    @Column(name = "note_cipher")
    private byte[] noteCipher;

    /**
     * Cardiff count-to-10 target snapshot (locked = 10 in MVP, K-5a).
     * DB CHECK ({@code target_count = 10}) enforces the structural invariant.
     * NOT a pass/fail threshold (INV-K2).
     */
    @Column(name = "target_count", nullable = false)
    private int targetCount = 10;

    /**
     * Lifecycle status. Only {@code 'completed'} is ever persisted on the server.
     * DB CHECK ({@code status = 'completed'}) makes this a structural invariant.
     * {@code in_progress} and {@code cancelled} are local-only draft states (OQ-K1).
     */
    @Column(nullable = false)
    private String status = "completed";

    /**
     * Derived snapshot of gestational week at session start.
     * CLIENT-SIDE computation via §3.1 canonical algorithm (DRIFT-1).
     * Stored verbatim — server NEVER recomputes or validates against week gate (D4/DRIFT-1).
     * Nullable-tolerant: absent on push is accepted (no week gate on the server).
     */
    @Column(name = "gestational_week_at_start")
    private Integer gestationalWeekAtStart;

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

    /** Server-assigned on EVERY apply. Bumped only on INSERT and tombstone (immutable event). */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone. {@code null} = live. {@code non-null} = tombstoned (tombstone-wins).
     * On tombstone: {@link #noteCipher} is crypto-shredded (set to {@code null}).
     * GC after {@code TOMBSTONE_TTL} (180 days, database-schema §4.4).
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

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public Integer getMovementCount() { return movementCount; }
    public void setMovementCount(Integer movementCount) { this.movementCount = movementCount; }

    public byte[] getNoteCipher() { return noteCipher; }
    public void setNoteCipher(byte[] noteCipher) { this.noteCipher = noteCipher; }

    public int getTargetCount() { return targetCount; }
    public void setTargetCount(int targetCount) { this.targetCount = targetCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getGestationalWeekAtStart() { return gestationalWeekAtStart; }
    public void setGestationalWeekAtStart(Integer gestationalWeekAtStart) {
        this.gestationalWeekAtStart = gestationalWeekAtStart;
    }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
}
