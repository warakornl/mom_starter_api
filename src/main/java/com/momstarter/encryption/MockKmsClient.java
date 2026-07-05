package com.momstarter.encryption;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Deterministic in-memory KMS mock for tests and local development.
 *
 * <p>Enforces the EncryptionContext binding: a wrapped DEK can only be unwrapped by presenting
 * the same {@code accountId} that was used during wrapping. A wrong accountId causes
 * {@link #decryptDek} to throw {@link IllegalArgumentException}, mirroring the AWS KMS
 * behaviour where a mismatched EncryptionContext produces an InvalidCiphertextException.
 *
 * <h2>Wrap format</h2>
 * <pre>
 *   wrappedDek = accountId.getBytes(UTF-8)   (variable length, 36 chars for canonical UUID)
 *              + 0x00 separator (1 byte)
 *              + XOR(plaintextDek, MASTER_KEY cycling)  (32 bytes)
 *   total: 36 + 1 + 32 = 69 bytes for a canonical lowercase UUID accountId
 * </pre>
 *
 * <p>The master key is a fixed all-zeros 32-byte key — only for testing; NOT a real secret.
 *
 * <h2>Thread safety</h2>
 * <p>Stateless after construction; thread-safe.
 *
 * <p><strong>LAUNCH-GATE:</strong> this class MUST be replaced by {@code AwsKmsClient}
 * (AWS SDK v2 over the real CMK in ap-southeast-7) before production deployment.
 * [verify-current-docs] SDK v2 exact request/response builder calls are tagged for
 * pre-launch verification.
 */
@Component
public class MockKmsClient implements KmsClient {

    /**
     * Fixed test master key (32 bytes, all zeros). NOT a real secret — only for mock/test.
     * The real AWS KMS CMK never leaves the HSM; this field has no production equivalent.
     */
    private static final byte[] MASTER_KEY = new byte[32]; // all zeros

    /** Stable mock CMK id returned by {@link #currentKeyId()} and stored in {@code account_dek.kms_key_id}. */
    static final String MOCK_KEY_ID = "mock-cmk/test-master-key-v1";

    private static final byte SEPARATOR = 0x00;
    private static final int DEK_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    // -------------------------------------------------------------------------
    // KmsClient implementation
    // -------------------------------------------------------------------------

    @Override
    public GeneratedDek generateDek(String accountId) {
        byte[] plaintextDek = new byte[DEK_LENGTH];
        secureRandom.nextBytes(plaintextDek);
        byte[] wrappedDek = wrap(plaintextDek, accountId);
        return new GeneratedDek(plaintextDek, wrappedDek, MOCK_KEY_ID);
    }

    @Override
    public byte[] decryptDek(byte[] wrappedDek, String accountId) {
        return unwrap(wrappedDek, accountId);
    }

    @Override
    public RewrappedDek reEncryptDek(byte[] wrappedDek, String accountId) {
        byte[] plaintextDek = unwrap(wrappedDek, accountId);
        byte[] newWrappedDek = wrap(plaintextDek, accountId);
        return new RewrappedDek(newWrappedDek, MOCK_KEY_ID);
    }

    @Override
    public String currentKeyId() {
        return MOCK_KEY_ID;
    }

    // -------------------------------------------------------------------------
    // Internal wrap / unwrap
    // -------------------------------------------------------------------------

    /**
     * Wraps {@code plaintextDek} into an opaque blob that embeds the accountId as the
     * EncryptionContext binding.
     *
     * <p>Format: {@code accountIdBytes || 0x00 || XOR(plaintextDek, MASTER_KEY)}.
     */
    byte[] wrap(byte[] plaintextDek, String accountId) {
        byte[] accountIdBytes = accountId.getBytes(StandardCharsets.UTF_8);
        // wrappedDek = accountIdBytes + separator(0x00) + XOR(plaintextDek, MASTER_KEY cycling)
        byte[] wrapped = new byte[accountIdBytes.length + 1 + DEK_LENGTH];
        System.arraycopy(accountIdBytes, 0, wrapped, 0, accountIdBytes.length);
        wrapped[accountIdBytes.length] = SEPARATOR;
        for (int i = 0; i < DEK_LENGTH; i++) {
            wrapped[accountIdBytes.length + 1 + i] =
                    (byte) (plaintextDek[i] ^ MASTER_KEY[i % MASTER_KEY.length]);
        }
        return wrapped;
    }

    /**
     * Unwraps a blob produced by {@link #wrap}, verifying the EncryptionContext binding.
     *
     * @throws IllegalArgumentException if the embedded accountId does not match the provided
     *         one (mirrors AWS KMS InvalidCiphertextException on EncryptionContext mismatch)
     */
    byte[] unwrap(byte[] wrappedDek, String accountId) {
        byte[] accountIdBytes = accountId.getBytes(StandardCharsets.UTF_8);
        int expectedLength = accountIdBytes.length + 1 + DEK_LENGTH;

        if (wrappedDek.length != expectedLength) {
            throw new IllegalArgumentException(
                    "MockKmsClient: invalid wrapped DEK length " + wrappedDek.length +
                    " (expected " + expectedLength + "). EncryptionContext mismatch?");
        }

        // Verify accountId prefix (EncryptionContext binding)
        for (int i = 0; i < accountIdBytes.length; i++) {
            if (wrappedDek[i] != accountIdBytes[i]) {
                throw new IllegalArgumentException(
                        "MockKmsClient: EncryptionContext mismatch — " +
                        "wrappedDek was sealed with a different accountId. " +
                        "AWS KMS would throw InvalidCiphertextException here.");
            }
        }

        if (wrappedDek[accountIdBytes.length] != SEPARATOR) {
            throw new IllegalArgumentException(
                    "MockKmsClient: invalid wrapped DEK format — missing separator byte.");
        }

        // Unwrap: XOR with MASTER_KEY cycling
        byte[] plaintextDek = new byte[DEK_LENGTH];
        for (int i = 0; i < DEK_LENGTH; i++) {
            plaintextDek[i] = (byte) (wrappedDek[accountIdBytes.length + 1 + i]
                    ^ MASTER_KEY[i % MASTER_KEY.length]);
        }
        return plaintextDek;
    }
}
