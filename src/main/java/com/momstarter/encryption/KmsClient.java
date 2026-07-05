package com.momstarter.encryption;

/**
 * Server-side KMS abstraction for per-account DEK lifecycle (Scheme A, ADR Decision 3.1).
 *
 * <p>Each account has one KMS-wrapped 256-bit AES DEK stored in {@code account_dek}. The
 * plaintext DEK lives <em>only transiently in memory</em> during login-delivery and export —
 * it is <strong>never logged, never persisted, and never returned in any error body</strong>.
 *
 * <p>The KMS {@code EncryptionContext} for every call is
 * {@code {"accountId": "<canonical-lowercase-uuid>"}}, binding the wrapped blob to its account.
 * A {@code Decrypt} call that presents the wrong context will fail at the KMS layer — defence
 * in depth above the field-level AAD.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link MockKmsClient} — deterministic in-memory wrap/unwrap keyed on a fixed test
 *       master key and the accountId; enforces the EncryptionContext binding so a wrong
 *       accountId fails to unwrap. Suitable for unit/integration tests and local dev.</li>
 *   <li>{@code AwsKmsClient} — AWS SDK v2 {@code KmsClient} (GenerateDataKey / Decrypt /
 *       ReEncrypt). <strong>[verify-current-docs]</strong> exact SDK v2 request/response
 *       builder calls must be confirmed before production wiring.
 *       <strong>LAUNCH-GATE</strong>: real AWS KMS CMK (ap-southeast-7) + IAM key-policy
 *       required; not built in this slice.</li>
 * </ul>
 *
 * <p>Concurrency: implementations must be thread-safe (one instance, many concurrent requests).
 */
public interface KmsClient {

    /**
     * Creates a fresh 256-bit DEK: plaintext (transient) + KMS-wrapped blob, bound to
     * {@code accountId} via EncryptionContext.
     *
     * <p>Equivalent to {@code AWS KMS GenerateDataKey} (AES_256).
     * <strong>This call MUST be made OUTSIDE any held DB transaction</strong> (ADR IMPORTANT-4):
     * it is a blocking network round-trip; holding a DB connection open across it causes
     * lock/connection pressure and makes KMS a registration SPOF.
     *
     * <p><strong>SECURITY:</strong> the returned {@link GeneratedDek#plaintextDek()} must
     * <em>never</em> be logged, cached, or persisted. The server holds it only long enough to
     * return it to the device (login-delivery) or use it as the decrypt key (export/PDF).
     *
     * @param accountId canonical lowercase 36-character UUID string (the EncryptionContext value)
     * @return a fresh {@link GeneratedDek} with the transient plaintext and the opaque wrapped blob
     */
    GeneratedDek generateDek(String accountId);

    /**
     * Unwraps a stored wrapped DEK back to plaintext (login-delivery and DEK-aware export/PDF).
     *
     * <p>Equivalent to {@code AWS KMS Decrypt} with {@code EncryptionContext={"accountId": accountId}}.
     * The {@code EncryptionContext} must match the context used during {@link #generateDek};
     * a mismatch causes the call to fail (KMS-layer binding, defence-in-depth).
     *
     * <p><strong>SECURITY:</strong> the returned plaintext DEK must <em>never</em> be logged.
     *
     * @param wrappedDek the opaque KMS CiphertextBlob stored in {@code account_dek.wrapped_dek}
     * @param accountId  canonical lowercase 36-character UUID (EncryptionContext binding check)
     * @return the 256-bit (32-byte) plaintext DEK, transiently in memory
     * @throws IllegalArgumentException if the EncryptionContext does not match (wrong accountId)
     */
    byte[] decryptDek(byte[] wrappedDek, String accountId);

    /**
     * Re-wraps an existing wrapped DEK under the current CMK (Decision 6 rotation;
     * the DEK plaintext and all field envelopes are unchanged — only the wrapping blob changes).
     *
     * <p>Equivalent to {@code AWS KMS ReEncrypt} (requires both {@code kms:ReEncryptFrom} +
     * {@code kms:ReEncryptTo} in the key policy — omitting either silently breaks rotation).
     * Used only on CMK change (not on automatic KMS key rotation, which is transparent).
     *
     * @param wrappedDek the existing opaque wrapped DEK blob
     * @param accountId  canonical lowercase 36-character UUID (EncryptionContext binding check)
     * @return a new {@link RewrappedDek} with the re-wrapped blob under the current CMK
     */
    RewrappedDek reEncryptDek(byte[] wrappedDek, String accountId);

    /**
     * Returns the CMK id/ARN currently used for wrapping new DEKs.
     * Stored in {@code account_dek.kms_key_id} to route future Decrypt/ReEncrypt calls.
     *
     * @return a non-null CMK identifier string
     */
    String currentKeyId();

    // -------------------------------------------------------------------------
    // Value records
    // -------------------------------------------------------------------------

    /**
     * Result of {@link #generateDek}: a fresh DEK in both plaintext (transient) and
     * KMS-wrapped (persisted in {@code account_dek}) forms.
     *
     * <p><strong>SECURITY:</strong> {@link #plaintextDek()} must never be logged or stored.
     * Discard it (let GC collect) after returning it to the device or using it for export.
     *
     * @param plaintextDek the raw 32-byte AES-256 DEK — TRANSIENT, never logged/stored
     * @param wrappedDek   the opaque KMS CiphertextBlob to persist in {@code account_dek.wrapped_dek}
     * @param kmsKeyId     the CMK id/ARN to persist in {@code account_dek.kms_key_id}
     */
    record GeneratedDek(byte[] plaintextDek, byte[] wrappedDek, String kmsKeyId) {}

    /**
     * Result of {@link #reEncryptDek}: the DEK re-wrapped under the current CMK.
     * Persist {@link #wrappedDek()} + {@link #kmsKeyId()} back to {@code account_dek} via
     * UPDATE (must also set {@code updated_at = now()}).
     *
     * @param wrappedDek the new opaque CiphertextBlob
     * @param kmsKeyId   the new CMK id/ARN
     */
    record RewrappedDek(byte[] wrappedDek, String kmsKeyId) {}
}
