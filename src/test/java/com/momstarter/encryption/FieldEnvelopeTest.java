package com.momstarter.encryption;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link FieldEnvelope} — AES-256-GCM field-level encryption primitive.
 *
 * <p>Covers all appsec RULING 1 (IV uniqueness), RULING 2 (AAD binding),
 * RULING 7 (tamper/downgrade), and RULING 8 (library/spec-identical test impl).
 *
 * <p>The golden-vector test uses a package-private fixed-IV helper to produce
 * deterministic output. The resulting bytes are committed in
 * {@code src/test/resources/encryption/golden-vectors.json} as the cross-impl
 * reference that future mobile (react-native-quick-crypto) and Node impls
 * must match byte-for-byte.
 */
@DisplayName("FieldEnvelope — AES-256-GCM primitive")
class FieldEnvelopeTest {

    // -----------------------------------------------------------------------
    // Shared test fixtures
    // -----------------------------------------------------------------------

    /** 256-bit DEK (all zeros) — for testing only, NEVER logged. */
    private static final byte[] TEST_DEK = new byte[32];

    private static final FieldAad SAMPLE_AAD = new FieldAad(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            "expenses",
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            "note"
    );

    // -----------------------------------------------------------------------
    // 1. Round-trip: encrypt → decrypt returns original plaintext
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("round-trip: ASCII plaintext survives encrypt→decrypt")
    void roundTrip_ascii() {
        byte[] plaintext = "110/70".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = FieldEnvelope.encrypt(plaintext, TEST_DEK, SAMPLE_AAD);
        byte[] decrypted = FieldEnvelope.decrypt(envelope, TEST_DEK, SAMPLE_AAD);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("round-trip: Thai UTF-8 plaintext survives encrypt→decrypt")
    void roundTrip_thaiUtf8() {
        // "สวัสดี" = Thai UTF-8, 18 bytes
        byte[] plaintext = "สวัสดี".getBytes(StandardCharsets.UTF_8);
        assertThat(plaintext).hasSize(18); // guard: UTF-8 encoding is correct
        byte[] envelope = FieldEnvelope.encrypt(plaintext, TEST_DEK, SAMPLE_AAD);
        byte[] decrypted = FieldEnvelope.decrypt(envelope, TEST_DEK, SAMPLE_AAD);
        assertThat(decrypted).isEqualTo(plaintext);
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo("สวัสดี");
    }

    @Test
    @DisplayName("round-trip: empty plaintext survives encrypt→decrypt")
    void roundTrip_empty() {
        byte[] plaintext = new byte[0];
        byte[] envelope = FieldEnvelope.encrypt(plaintext, TEST_DEK, SAMPLE_AAD);
        // min length = 1 (version) + 12 (IV) + 0 (ct) + 16 (tag) = 29
        assertThat(envelope).hasSize(29);
        byte[] decrypted = FieldEnvelope.decrypt(envelope, TEST_DEK, SAMPLE_AAD);
        assertThat(decrypted).isEmpty();
    }

    @Test
    @DisplayName("round-trip: long plaintext (4096 bytes) survives encrypt→decrypt")
    void roundTrip_long() {
        byte[] plaintext = new byte[4096];
        Arrays.fill(plaintext, (byte) 0x41); // 'A'
        byte[] envelope = FieldEnvelope.encrypt(plaintext, TEST_DEK, SAMPLE_AAD);
        byte[] decrypted = FieldEnvelope.decrypt(envelope, TEST_DEK, SAMPLE_AAD);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    // -----------------------------------------------------------------------
    // 2. Envelope structure checks
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("envelope starts with version byte 0x01 and contains correct-length IV")
    void envelopeStructure() {
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = FieldEnvelope.encrypt(plaintext, TEST_DEK, SAMPLE_AAD);

        // version byte
        assertThat(envelope[0]).isEqualTo((byte) 0x01);

        // total length: 1 + 12 + plaintext.length + 16
        assertThat(envelope.length).isEqualTo(1 + 12 + plaintext.length + 16);
    }

    // -----------------------------------------------------------------------
    // 3. IV uniqueness (appsec RULING 1)
    //    1,000 encrypts of the same plaintext under the same DEK →
    //    all 12-byte IVs distinct AND all ciphertexts distinct.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("RULING 1 — 1,000 encrypts produce distinct IVs and distinct ciphertexts")
    void ivUniqueness_1000Encrypts() {
        byte[] plaintext = "110/70".getBytes(StandardCharsets.UTF_8);
        int n = 1_000;
        Set<String> seenIvs = new HashSet<>(n);
        Set<String> seenEnvelopes = new HashSet<>(n);

        for (int i = 0; i < n; i++) {
            byte[] envelope = FieldEnvelope.encrypt(plaintext, TEST_DEK, SAMPLE_AAD);
            // Extract IV: bytes 1..12 inclusive
            String ivHex = HexFormat.of().formatHex(Arrays.copyOfRange(envelope, 1, 13));
            String envHex = HexFormat.of().formatHex(envelope);
            assertThat(seenIvs).as("IV collision at iteration %d", i).doesNotContain(ivHex);
            assertThat(seenEnvelopes).as("ciphertext collision at iteration %d", i).doesNotContain(envHex);
            seenIvs.add(ivHex);
            seenEnvelopes.add(envHex);
        }
    }

    // -----------------------------------------------------------------------
    // 4. NIST AES-256-GCM Known-Answer Tests (RULING 8 — JVM impl parity)
    //    Tests the raw JCE cipher directly to prove the JVM AES-GCM impl
    //    is spec-correct (NIST SP 800-38D / NIST CAVS vectors).
    //    Note: these test the JCE directly (not FieldEnvelope) because NIST
    //    vectors use empty AAD while FieldEnvelope requires non-empty FieldAad.
    // -----------------------------------------------------------------------

    /**
     * NIST SP 800-38D Test Case 13 — AES-256-GCM, empty plaintext, empty AAD.
     * Key, IV = all-zeros; expected tag = 530f8afbc74536b9a963b4f1c4cb738b.
     */
    @Test
    @DisplayName("RULING 8 NIST KAT — TC13: empty PT, empty AAD (proves JVM AES-GCM is spec-correct)")
    void nistKat_tc13_aes256gcm_emptyPtEmptyAad() throws Exception {
        byte[] key = new byte[32]; // K = 0^256
        byte[] iv = new byte[12];  // IV = 0^96

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        // No updateAAD — empty AAD as per NIST vector
        byte[] result = cipher.doFinal(new byte[0]); // empty PT

        // JCE returns: ciphertext (0 bytes) || tag (16 bytes) = 16 bytes total
        assertThat(result).hasSize(16);
        // NIST expected tag = 530f8afbc74536b9a963b4f1c4cb738b
        assertThat(HexFormat.of().formatHex(result))
                .isEqualTo("530f8afbc74536b9a963b4f1c4cb738b");
    }

    /**
     * NIST SP 800-38D Test Case 14 — AES-256-GCM, 16-byte zero plaintext, empty AAD.
     * Key, IV = all-zeros; expected CT = cea7403d4d606b6e074ec5d3baf39d18;
     * expected tag = d0d1c8a799996bf0265b98b5d48ab919.
     */
    @Test
    @DisplayName("RULING 8 NIST KAT — TC14: 16-byte zero PT, empty AAD (proves JVM AES-GCM is spec-correct)")
    void nistKat_tc14_aes256gcm_16bytePtEmptyAad() throws Exception {
        byte[] key = new byte[32]; // K = 0^256
        byte[] iv = new byte[12];  // IV = 0^96
        byte[] pt = new byte[16];  // PT = 0^128

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        // No updateAAD — empty AAD as per NIST vector
        byte[] result = cipher.doFinal(pt);

        // JCE returns: ciphertext (16 bytes) || tag (16 bytes) = 32 bytes
        assertThat(result).hasSize(32);
        byte[] ct = Arrays.copyOf(result, 16);
        byte[] tag = Arrays.copyOfRange(result, 16, 32);

        // NIST expected CT = cea7403d4d606b6e074ec5d3baf39d18
        assertThat(HexFormat.of().formatHex(ct))
                .isEqualTo("cea7403d4d606b6e074ec5d3baf39d18");
        // NIST expected tag = d0d1c8a799996bf0265b98b5d48ab919
        assertThat(HexFormat.of().formatHex(tag))
                .isEqualTo("d0d1c8a799996bf0265b98b5d48ab919");
    }

    // -----------------------------------------------------------------------
    // 5. Golden envelope vector (RULING 8 — cross-impl reference)
    //    Fixed inputs → deterministic envelope bytes committed in
    //    src/test/resources/encryption/golden-vectors.json.
    //    Mobile (react-native-quick-crypto) and Node impls must produce
    //    the SAME bytes for the same (dek, iv, plaintext, aad) combination.
    //
    //    DEK  = 0x00 * 32
    //    IV   = 0x00 * 12  (fixed — overrides CSPRNG via encryptWithIv)
    //    PT   = "hello"  (UTF-8, 5 bytes)
    //    AAD  = FieldAad("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    //                    "expenses",
    //                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
    //                    "note")
    //    AAD string = "v1:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:expenses:bbbbbbbb-...:note"
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("RULING 8 golden vector — fixed IV produces byte-identical envelope (cross-impl reference)")
    void goldenVector_matchesExpected() {
        byte[] dek = new byte[32];           // all zeros
        byte[] fixedIv = new byte[12];       // all zeros
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
        FieldAad aad = new FieldAad(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "expenses",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                "note"
        );

        // Use package-private fixed-IV helper for deterministic output
        byte[] envelope = FieldEnvelope.encryptWithIv(plaintext, dek, aad, fixedIv);

        // --- Structure assertions ---
        assertThat(envelope[0]).as("version byte must be 0x01").isEqualTo((byte) 0x01);
        assertThat(Arrays.copyOfRange(envelope, 1, 13))
                .as("IV slot must contain the fixed IV")
                .isEqualTo(fixedIv);
        // length: 1 + 12 + 5 (hello) + 16 (tag) = 34
        assertThat(envelope).hasSize(34);

        // --- Round-trip via decrypt ---
        byte[] decrypted = FieldEnvelope.decrypt(envelope, dek, aad);
        assertThat(decrypted).isEqualTo(plaintext);

        // --- Golden byte assertion (committed cross-impl reference) ---
        // These bytes were generated by the JVM impl and committed as the canonical
        // reference. Future mobile (react-native-quick-crypto / BoringSSL) and Node
        // (crypto module) impls MUST produce the same bytes for the same inputs.
        // Regenerate only by bumping the envelope version — do NOT change without
        // updating the corresponding mobile/Node test fixtures.
        String expectedHex = GoldenVectors.ENVELOPE_HELLO_ZERO_DEK_ZERO_IV;
        assertThat(HexFormat.of().formatHex(envelope))
                .as("golden vector must match committed cross-impl reference")
                .isEqualTo(expectedHex);
    }

    // -----------------------------------------------------------------------
    // 6. AAD binding (appsec RULING 2 — reject cross-account/record/field moves)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("RULING 2 — wrong accountId in AAD causes tag failure (rejects cross-account move)")
    void aadBinding_wrongAccountId_throws() {
        byte[] envelope = FieldEnvelope.encrypt(
                "sensitive".getBytes(StandardCharsets.UTF_8), TEST_DEK, SAMPLE_AAD);

        FieldAad wrongAad = new FieldAad(
                "cccccccc-cccc-cccc-cccc-cccccccccccc", // wrong accountId
                SAMPLE_AAD.collection(),
                SAMPLE_AAD.recordId(),
                SAMPLE_AAD.fieldName()
        );
        assertThatThrownBy(() -> FieldEnvelope.decrypt(envelope, TEST_DEK, wrongAad))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("RULING 2 — wrong collection in AAD causes tag failure")
    void aadBinding_wrongCollection_throws() {
        byte[] envelope = FieldEnvelope.encrypt(
                "sensitive".getBytes(StandardCharsets.UTF_8), TEST_DEK, SAMPLE_AAD);

        FieldAad wrongAad = new FieldAad(
                SAMPLE_AAD.accountId(),
                "kickCountSessions", // wrong collection
                SAMPLE_AAD.recordId(),
                SAMPLE_AAD.fieldName()
        );
        assertThatThrownBy(() -> FieldEnvelope.decrypt(envelope, TEST_DEK, wrongAad))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("RULING 2 — wrong recordId in AAD causes tag failure (rejects cross-record move)")
    void aadBinding_wrongRecordId_throws() {
        byte[] envelope = FieldEnvelope.encrypt(
                "sensitive".getBytes(StandardCharsets.UTF_8), TEST_DEK, SAMPLE_AAD);

        FieldAad wrongAad = new FieldAad(
                SAMPLE_AAD.accountId(),
                SAMPLE_AAD.collection(),
                "cccccccc-cccc-cccc-cccc-cccccccccccc", // wrong recordId
                SAMPLE_AAD.fieldName()
        );
        assertThatThrownBy(() -> FieldEnvelope.decrypt(envelope, TEST_DEK, wrongAad))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("RULING 2 — wrong fieldName in AAD causes tag failure (rejects cross-field move)")
    void aadBinding_wrongFieldName_throws() {
        byte[] envelope = FieldEnvelope.encrypt(
                "sensitive".getBytes(StandardCharsets.UTF_8), TEST_DEK, SAMPLE_AAD);

        FieldAad wrongAad = new FieldAad(
                SAMPLE_AAD.accountId(),
                SAMPLE_AAD.collection(),
                SAMPLE_AAD.recordId(),
                "birthNote" // wrong fieldName
        );
        assertThatThrownBy(() -> FieldEnvelope.decrypt(envelope, TEST_DEK, wrongAad))
                .isInstanceOf(SecurityException.class);
    }

    // -----------------------------------------------------------------------
    // 7. Tamper resistance (appsec RULING 7a)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("RULING 7a — flipping a ciphertext byte causes tag verification failure")
    void tamper_ciphertextByte_throws() {
        byte[] plaintext = "blood-pressure".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = FieldEnvelope.encrypt(plaintext, TEST_DEK, SAMPLE_AAD);

        // Flip a byte in the ciphertext region (after version + IV, before last 16 tag bytes)
        // For plaintext of 14 bytes: envelope = [0x01][12 IV bytes][14 CT bytes][16 tag bytes]
        // CT starts at index 13; flip the first CT byte
        byte[] tampered = Arrays.copyOf(envelope, envelope.length);
        tampered[13] ^= 0xFF; // flip all bits in first CT byte

        assertThatThrownBy(() -> FieldEnvelope.decrypt(tampered, TEST_DEK, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("RULING 7a — flipping a tag byte causes tag verification failure")
    void tamper_tagByte_throws() {
        byte[] plaintext = "blood-pressure".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = FieldEnvelope.encrypt(plaintext, TEST_DEK, SAMPLE_AAD);

        // Flip the last byte (tag region)
        byte[] tampered = Arrays.copyOf(envelope, envelope.length);
        tampered[tampered.length - 1] ^= 0x01;

        assertThatThrownBy(() -> FieldEnvelope.decrypt(tampered, TEST_DEK, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("RULING 7a — flipping an IV byte causes tag verification failure")
    void tamper_ivByte_throws() {
        byte[] plaintext = "blood-pressure".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = FieldEnvelope.encrypt(plaintext, TEST_DEK, SAMPLE_AAD);

        // Flip the first IV byte (index 1)
        byte[] tampered = Arrays.copyOf(envelope, envelope.length);
        tampered[1] ^= 0xFF;

        assertThatThrownBy(() -> FieldEnvelope.decrypt(tampered, TEST_DEK, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("RULING 7 — envelope shorter than 29 bytes is rejected")
    void shortEnvelope_throws() {
        byte[] tooShort = new byte[28]; // < 29 byte minimum
        tooShort[0] = 0x01;
        assertThatThrownBy(() -> FieldEnvelope.decrypt(tooShort, TEST_DEK, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("RULING 7b — unknown version byte is rejected")
    void unknownVersionByte_throws() {
        byte[] envelope = FieldEnvelope.encrypt(
                "hello".getBytes(StandardCharsets.UTF_8), TEST_DEK, SAMPLE_AAD);
        byte[] mutated = Arrays.copyOf(envelope, envelope.length);
        mutated[0] = 0x02; // future version not yet supported
        assertThatThrownBy(() -> FieldEnvelope.decrypt(mutated, TEST_DEK, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class);
    }

    // -----------------------------------------------------------------------
    // 8. DEK length guard — AES-256-only invariant
    //    16-byte and 24-byte DEKs MUST be rejected with SecurityException
    //    on both encrypt and decrypt. Only 32-byte DEKs are accepted.
    //    Without this guard, SecretKeySpec silently accepts 16/24/32-byte keys,
    //    producing AES-128/192-GCM — a silent security downgrade with no error.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DEK guard — 16-byte DEK rejected on encrypt with SecurityException")
    void dekGuard_16byteDek_encrypt_throws() {
        byte[] shortDek = new byte[16];
        assertThatThrownBy(() ->
                FieldEnvelope.encrypt("hello".getBytes(StandardCharsets.UTF_8), shortDek, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("DEK guard — 16-byte DEK rejected on decrypt with SecurityException")
    void dekGuard_16byteDek_decrypt_throws() {
        // Build a minimal well-formed envelope (29 bytes) so we reach the DEK guard
        byte[] shortDek = new byte[16];
        byte[] minEnvelope = new byte[29];
        minEnvelope[0] = 0x01; // valid version byte
        assertThatThrownBy(() ->
                FieldEnvelope.decrypt(minEnvelope, shortDek, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("DEK guard — 24-byte DEK rejected on encrypt with SecurityException")
    void dekGuard_24byteDek_encrypt_throws() {
        byte[] mediumDek = new byte[24];
        assertThatThrownBy(() ->
                FieldEnvelope.encrypt("hello".getBytes(StandardCharsets.UTF_8), mediumDek, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("DEK guard — 24-byte DEK rejected on decrypt with SecurityException")
    void dekGuard_24byteDek_decrypt_throws() {
        byte[] mediumDek = new byte[24];
        byte[] minEnvelope = new byte[29];
        minEnvelope[0] = 0x01;
        assertThatThrownBy(() ->
                FieldEnvelope.decrypt(minEnvelope, mediumDek, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("DEK guard — null DEK rejected on encrypt with SecurityException")
    void dekGuard_nullDek_encrypt_throws() {
        assertThatThrownBy(() ->
                FieldEnvelope.encrypt("hello".getBytes(StandardCharsets.UTF_8), null, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("DEK guard — null DEK rejected on decrypt with SecurityException")
    void dekGuard_nullDek_decrypt_throws() {
        byte[] minEnvelope = new byte[29];
        minEnvelope[0] = 0x01;
        assertThatThrownBy(() ->
                FieldEnvelope.decrypt(minEnvelope, null, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("DEK guard — 32-byte DEK is accepted and produces correct round-trip")
    void dekGuard_32byteDek_accepted() {
        byte[] dek32 = new byte[32];
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = FieldEnvelope.encrypt(plaintext, dek32, SAMPLE_AAD);
        byte[] decrypted = FieldEnvelope.decrypt(envelope, dek32, SAMPLE_AAD);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Holds the committed golden-vector hex strings for cross-impl verification.
     * Mobile and Node impls must produce identical bytes for the same inputs.
     */
    static final class GoldenVectors {
        /**
         * AES-256-GCM envelope for plaintext "hello", all-zero 32-byte DEK,
         * all-zero 12-byte IV, and AAD:
         * {@code "v1:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:expenses:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb:note"}.
         *
         * <p>Byte layout: {@code 01 (version) || 00*12 (IV) || 5-byte CT || 16-byte GCM tag}.
         *
         * <p><strong>Cross-impl contract:</strong> react-native-quick-crypto (BoringSSL) and
         * Node crypto module MUST produce the identical 34-byte sequence for the same inputs.
         * This value is also written to
         * {@code src/test/resources/encryption/golden-vectors.json}.
         *
         * <p>To regenerate: change the envelope version to 0x02 and re-run — otherwise this
         * constant and the corresponding mobile/Node fixtures must change together.
         */
        // Generated by first JVM impl run; committed as the canonical cross-impl reference.
        // Byte breakdown: 01 (v1) | 00*12 (IV) | a6c22c5122 (CT for "hello") | 3049fa839faf2c12d07d149dc6a22b8e (tag)
        // Also written to src/test/resources/encryption/golden-vectors.json.
        static final String ENVELOPE_HELLO_ZERO_DEK_ZERO_IV =
                "01000000000000000000000000a6c22c51223049fa839faf2c12d07d149dc6a22b8e";

        private GoldenVectors() {}
    }
}
