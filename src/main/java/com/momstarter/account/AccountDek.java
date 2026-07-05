package com.momstarter.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-account KMS-wrapped DEK store (field-encryption Scheme A, ADR Decision 1).
 *
 * <p>Exactly one row per account. The {@link #wrappedDek} is an opaque KMS CiphertextBlob;
 * the plaintext DEK is never stored here. The row is created at account creation (eagerly in
 * {@code RegistrationService.verifyEmail()}) and hard-deleted at account erasure (T0 crypto-shred
 * in {@code AccountService.deleteAccount()} — sub-slice c).
 *
 * <h2>SHRED INVARIANT (ADR IMPORTANT-2)</h2>
 * <p>Hard {@code DELETE} of this row IS the crypto-shred primitive. There is NO {@code status}
 * column and NO soft-delete mechanism by design: a soft flag would leave {@code wrapped_dek}
 * intact → {@code KMS.Decrypt} would still succeed → NOT a crypto-shred. The only lifecycle
 * events are creation and hard DELETE.
 *
 * <h2>FK constraint</h2>
 * <p>{@code account_dek.user_id → users(id) ON DELETE RESTRICT}. The row MUST be deleted
 * BEFORE the {@code users} row (T0 in {@code AccountService}, Tier-1 backstop in
 * {@code AccountErasureService.TIER1_CHILD_DELETE_ORDER}).
 *
 * <h2>Optimistic concurrency / isNew() detection</h2>
 * <p>{@link #version} is a {@code Long} wrapper (nullable) so that Spring Data JPA's
 * {@code isNew()} returns {@code true} when {@code version == null} (before first persist),
 * causing {@code save()} to call {@code persist()} (INSERT) rather than {@code merge()}
 * (UPDATE). After INSERT, Hibernate sets {@code version = 0} (matching the DB {@code DEFAULT 0}).
 *
 * <h2>Not a sync collection</h2>
 * <p>No {@code client_id}, no {@code deleted_at}, no tombstone. The {@code created_at},
 * {@code updated_at}, and {@code version} columns are for ops/optimistic-concurrency only
 * and are never included in a {@code SyncChangeSet}.
 *
 * <h2>Security</h2>
 * <p>{@link #wrappedDek} is an opaque blob — NEVER the plaintext DEK.
 * The plaintext DEK lives only transiently in memory during login-delivery and export/PDF egress.
 */
@Entity
@Table(name = "account_dek")
public class AccountDek {

    /**
     * PK = user_id (1:1 with {@code users}). Also the natural idempotency key for
     * {@code INSERT ... ON CONFLICT (user_id) DO NOTHING}.
     * Always set explicitly before saving (no {@code @GeneratedValue}).
     */
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * KMS CiphertextBlob: the opaque KMS-wrapped 256-bit DEK.
     * <strong>NEVER the plaintext DEK.</strong> Never logged, never egressed in any API response.
     * Stored as {@code bytea}; standard JPA {@code byte[]} mapping works in both H2 and PostgreSQL.
     */
    @Column(name = "wrapped_dek", nullable = false)
    private byte[] wrappedDek;

    /**
     * CMK id/ARN used to wrap this DEK. Needed to route {@code KMS.Decrypt} and
     * {@code KMS.ReEncrypt} correctly. Stable under automatic KMS key rotation (ARN unchanged);
     * updated only on CMK change (Decision 6/MINOR-C).
     */
    @Column(name = "kms_key_id", nullable = false)
    private String kmsKeyId;

    /**
     * KMS EncryptionContext value bound at wrap time.
     * Format: {@code "accountId=<canonical-lowercase-uuid>"}. Stored as an audit trace;
     * the same value must be passed to {@code KMS.Decrypt} for the call to succeed.
     */
    @Column(name = "wrap_context", nullable = false)
    private String wrapContext;

    /**
     * DEK generation counter. {@code 1} = current AES-256-GCM with {@code 0x01} envelope prefix.
     * Reserved for future true-DEK-change ({@code 0x02}/{@code "v2:"} — deferred, ADR Decision 6).
     * DB constraint: {@code CHECK (dek_version >= 1)}.
     */
    @Column(name = "dek_version", nullable = false)
    private short dekVersion = 1;

    /**
     * Server-stamped creation time. Set on first {@code INSERT}; never changed.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Server-stamped update time. Changed on CMK rotation re-wrap ({@code KmsClient.reEncryptDek}
     * UPDATE — must explicitly set {@code updated_at = now()} since there is no trigger).
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Hibernate {@code @Version} optimistic-lock column (DB: {@code bigint NOT NULL DEFAULT 0}).
     *
     * <p>Using the {@code Long} wrapper (nullable) so Spring Data JPA's {@code isNew()}
     * returns {@code true} when {@code version == null} (before first persist), triggering
     * {@code persist()} (INSERT) rather than {@code merge()} (UPDATE). After INSERT, Hibernate
     * sets this to {@code 0} in memory, matching {@code DEFAULT 0} in the schema.
     */
    @Version
    private Long version;

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    /** Sets server-side timestamps on first persist. */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    // -------------------------------------------------------------------------
    // Accessors (no setters for immutable-after-creation fields)
    // -------------------------------------------------------------------------

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public byte[] getWrappedDek() { return wrappedDek; }
    public void setWrappedDek(byte[] wrappedDek) { this.wrappedDek = wrappedDek; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public String getWrapContext() { return wrapContext; }
    public void setWrapContext(String wrapContext) { this.wrapContext = wrapContext; }

    public short getDekVersion() { return dekVersion; }
    public void setDekVersion(short dekVersion) { this.dekVersion = dekVersion; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
}
