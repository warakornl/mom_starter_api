package com.momstarter.encryption;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MockKmsClient}.
 *
 * <p>Verifies: DEK generation, round-trip wrap→unwrap correctness, and the
 * EncryptionContext binding (wrong accountId must fail to unwrap).
 *
 * <p>TDD: tests were written before the implementation to drive the API shape.
 */
class MockKmsClientTest {

    private final MockKmsClient kms = new MockKmsClient();
    private final String accountId = UUID.randomUUID().toString();

    // -------------------------------------------------------------------------
    // generateDek
    // -------------------------------------------------------------------------

    @Test
    void generateDek_returnsNonNullResult() {
        KmsClient.GeneratedDek result = kms.generateDek(accountId);

        assertThat(result).isNotNull();
        assertThat(result.plaintextDek()).isNotNull().hasSize(32);
        assertThat(result.wrappedDek()).isNotNull().isNotEmpty();
        assertThat(result.kmsKeyId()).isNotNull().isEqualTo(MockKmsClient.MOCK_KEY_ID);
    }

    @Test
    void generateDek_eachCallProducesDistinctPlaintextDek() {
        KmsClient.GeneratedDek first = kms.generateDek(accountId);
        KmsClient.GeneratedDek second = kms.generateDek(accountId);

        // plaintextDek is random; must be distinct with overwhelming probability
        assertThat(first.plaintextDek()).isNotEqualTo(second.plaintextDek());
    }

    // -------------------------------------------------------------------------
    // decryptDek — round-trip
    // -------------------------------------------------------------------------

    @Test
    void decryptDek_afterGenerateDek_returnsOriginalPlaintextDek() {
        KmsClient.GeneratedDek generated = kms.generateDek(accountId);

        byte[] decrypted = kms.decryptDek(generated.wrappedDek(), accountId);

        assertThat(decrypted).containsExactly(toBoxed(generated.plaintextDek()));
    }

    @Test
    void decryptDek_wrongAccountId_throwsIllegalArgumentException() {
        KmsClient.GeneratedDek generated = kms.generateDek(accountId);
        String wrongAccountId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> kms.decryptDek(generated.wrappedDek(), wrongAccountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EncryptionContext mismatch");
    }

    @Test
    void decryptDek_emptyWrappedDek_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> kms.decryptDek(new byte[0], accountId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptDek_truncatedWrappedDek_throwsIllegalArgumentException() {
        KmsClient.GeneratedDek generated = kms.generateDek(accountId);
        byte[] truncated = Arrays.copyOf(generated.wrappedDek(), generated.wrappedDek().length - 1);

        assertThatThrownBy(() -> kms.decryptDek(truncated, accountId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // reEncryptDek
    // -------------------------------------------------------------------------

    @Test
    void reEncryptDek_producesNewWrappedDek_thatDecryptsToSamePlaintext() {
        KmsClient.GeneratedDek generated = kms.generateDek(accountId);

        KmsClient.RewrappedDek rewrapped = kms.reEncryptDek(generated.wrappedDek(), accountId);

        assertThat(rewrapped).isNotNull();
        assertThat(rewrapped.wrappedDek()).isNotNull().isNotEmpty();
        assertThat(rewrapped.kmsKeyId()).isEqualTo(MockKmsClient.MOCK_KEY_ID);

        // Round-trip: decrypting the re-wrapped blob yields the same plaintext DEK
        byte[] decrypted = kms.decryptDek(rewrapped.wrappedDek(), accountId);
        assertThat(decrypted).containsExactly(toBoxed(generated.plaintextDek()));
    }

    @Test
    void reEncryptDek_wrongAccountId_throwsIllegalArgumentException() {
        KmsClient.GeneratedDek generated = kms.generateDek(accountId);
        String wrongAccountId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> kms.reEncryptDek(generated.wrappedDek(), wrongAccountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EncryptionContext mismatch");
    }

    // -------------------------------------------------------------------------
    // currentKeyId
    // -------------------------------------------------------------------------

    @Test
    void currentKeyId_returnsNonNullStableString() {
        assertThat(kms.currentKeyId()).isNotNull().isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Cross-account isolation
    // -------------------------------------------------------------------------

    @Test
    void wrappedDek_cannotBeUsedForDifferentAccount() {
        String accountA = UUID.randomUUID().toString();
        String accountB = UUID.randomUUID().toString();

        KmsClient.GeneratedDek forA = kms.generateDek(accountA);

        // accountA's wrapped DEK must NOT decrypt under accountB's context
        assertThatThrownBy(() -> kms.decryptDek(forA.wrappedDek(), accountB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EncryptionContext mismatch");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Byte[] toBoxed(byte[] bytes) {
        Byte[] boxed = new Byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            boxed[i] = bytes[i];
        }
        return boxed;
    }
}
