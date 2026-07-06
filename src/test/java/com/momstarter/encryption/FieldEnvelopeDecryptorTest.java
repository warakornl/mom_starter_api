package com.momstarter.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link FieldEnvelopeDecryptor} — Decision-4 version dispatch
 * and appsec RULING 7b downgrade guard (anti-downgrade cutover flag).
 *
 * <p>The version dispatch rule (ADR Decision 3.2 IMPORTANT-3 / Decision 4):
 * <ul>
 *   <li>base64-decode the wire value</li>
 *   <li>if first byte == {@code 0x01} AND decoded length &ge; 29 → GCM decrypt</li>
 *   <li>otherwise → legacy UNVERSIONED identity blob:
 *       {@code new String(decoded, UTF_8)} = plaintext</li>
 * </ul>
 *
 * <p>The cutover flag (appsec RULING 7b M-2): once all legacy blobs are migrated,
 * set the global cutover flag. From that point forward, any legacy blob is
 * rejected immediately — no per-record anti-downgrade needed.
 */
@DisplayName("FieldEnvelopeDecryptor — Decision-4 version dispatch + downgrade guard")
class FieldEnvelopeDecryptorTest {

    private static final byte[] TEST_DEK = new byte[32]; // all zeros

    private static final FieldAad SAMPLE_AAD = new FieldAad(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            "expenses",
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            "note"
    );

    private LegacyCutoverPolicy cutoverPolicy;
    private FieldEnvelopeDecryptor decryptor;

    @BeforeEach
    void setUp() {
        cutoverPolicy = new LegacyCutoverPolicy();
        decryptor = new FieldEnvelopeDecryptor(cutoverPolicy);
    }

    // -----------------------------------------------------------------------
    // 1. Version dispatch — legacy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("dispatch — legacy base64(utf8(plaintext)) returns plaintext string")
    void dispatch_legacyBase64_returnsPlaintext() {
        // "110/70" is a real blood-pressure value stored as the no-op cipher:
        // wire = base64(utf8("110/70")) — no version byte, no GCM
        String legacy = Base64.getEncoder().encodeToString(
                "110/70".getBytes(StandardCharsets.UTF_8));

        String result = decryptor.decryptFromBase64(legacy, TEST_DEK, SAMPLE_AAD);

        assertThat(result).isEqualTo("110/70");
    }

    @Test
    @DisplayName("dispatch — legacy Thai UTF-8 plaintext survives the legacy path")
    void dispatch_legacyThaiUtf8_returnsPlaintext() {
        String legacy = Base64.getEncoder().encodeToString(
                "ปวดหัว".getBytes(StandardCharsets.UTF_8));

        String result = decryptor.decryptFromBase64(legacy, TEST_DEK, SAMPLE_AAD);

        assertThat(result).isEqualTo("ปวดหัว");
    }

    // -----------------------------------------------------------------------
    // 2. Version dispatch — GCM path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("dispatch — real GCM envelope (0x01 prefix, len >= 29) decrypts to plaintext")
    void dispatch_gcmEnvelope_decryptsCorrectly() {
        // Create a real GCM envelope using FieldEnvelope
        byte[] plaintext = "สวัสดี".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = FieldEnvelope.encrypt(plaintext, TEST_DEK, SAMPLE_AAD);
        String wireBase64 = Base64.getEncoder().encodeToString(envelope);

        String result = decryptor.decryptFromBase64(wireBase64, TEST_DEK, SAMPLE_AAD);

        assertThat(result).isEqualTo("สวัสดี");
    }

    @Test
    @DisplayName("dispatch — GCM envelope with empty plaintext decrypts correctly")
    void dispatch_gcmEmptyPlaintext_decryptsCorrectly() {
        byte[] envelope = FieldEnvelope.encrypt(new byte[0], TEST_DEK, SAMPLE_AAD);
        assertThat(envelope).hasSize(29); // minimal envelope length

        String wireBase64 = Base64.getEncoder().encodeToString(envelope);
        String result = decryptor.decryptFromBase64(wireBase64, TEST_DEK, SAMPLE_AAD);

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // 3. Version dispatch — boundary heuristic
    //    Dispatch = first byte 0x01 AND decoded len >= 29.
    //    Both conditions must hold; either alone is insufficient.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("dispatch boundary — decoded len < 29 is treated as legacy even if first byte is 0x01")
    void dispatch_boundary_firstByteIs01ButTooShort_treatedAsLegacy() {
        // A legacy blob whose decoded bytes happen to start with 0x01 (SOH control char)
        // but total decoded length is < 29 → must be treated as legacy (not GCM).
        // Note: a health-text value starting with 0x01 is astronomically unlikely
        // (0x01 = ASCII SOH control char, not produced by Thai/Latin health data),
        // but the dispatch must handle it correctly at the boundary.
        byte[] decodedBytes = new byte[]{0x01, 'a', 'b', 'c'}; // 4 bytes < 29
        String wireBase64 = Base64.getEncoder().encodeToString(decodedBytes);

        // Decoded bytes start with 0x01 but len=4 < 29 → legacy path
        // Legacy path: new String(decodedBytes, UTF_8)
        String result = decryptor.decryptFromBase64(wireBase64, TEST_DEK, SAMPLE_AAD);
        assertThat(result).isEqualTo(new String(decodedBytes, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("dispatch boundary — decoded len >= 29 but first byte != 0x01 is treated as legacy")
    void dispatch_boundary_longEnoughButWrongVersionByte_treatedAsLegacy() {
        // Decoded bytes have length >= 29 but first byte is NOT 0x01 → legacy path
        byte[] decodedBytes = new byte[29];
        decodedBytes[0] = 0x00; // NOT 0x01 → legacy
        for (int i = 1; i < 29; i++) {
            decodedBytes[i] = (byte) ('a' + (i % 26));
        }
        String wireBase64 = Base64.getEncoder().encodeToString(decodedBytes);

        String result = decryptor.decryptFromBase64(wireBase64, TEST_DEK, SAMPLE_AAD);
        assertThat(result).isEqualTo(new String(decodedBytes, StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------
    // 4. Downgrade guard — appsec RULING 7b / M-2
    //    Per-record anti-downgrade is impossible (version byte is inside bytea).
    //    Only a global "reject legacy after cutover" flag is workable.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("RULING 7b M-2 — cutover flag OFF (default): legacy blob is accepted")
    void downgradeguard_cutoverOff_legacyAccepted() {
        // Default: cutover not complete → legacy accepted
        assertThat(cutoverPolicy.isCutoverComplete()).isFalse();

        String legacy = Base64.getEncoder().encodeToString(
                "normal text".getBytes(StandardCharsets.UTF_8));

        String result = decryptor.decryptFromBase64(legacy, TEST_DEK, SAMPLE_AAD);
        assertThat(result).isEqualTo("normal text");
    }

    @Test
    @DisplayName("RULING 7b M-2 — cutover flag ON: legacy blob is REJECTED (anti-downgrade)")
    void downgradeguard_cutoverOn_legacyRejected() {
        cutoverPolicy.setCutoverComplete(true);

        String legacy = Base64.getEncoder().encodeToString(
                "should-be-rejected".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> decryptor.decryptFromBase64(legacy, TEST_DEK, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("cutover");
    }

    @Test
    @DisplayName("RULING 7b M-2 — cutover flag ON: GCM envelope is still accepted (not affected by cutover)")
    void downgradeguard_cutoverOn_gcmEnvelopeAccepted() {
        cutoverPolicy.setCutoverComplete(true);

        byte[] plaintext = "valid".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = FieldEnvelope.encrypt(plaintext, TEST_DEK, SAMPLE_AAD);
        String wireBase64 = Base64.getEncoder().encodeToString(envelope);

        // GCM envelope is always accepted regardless of cutover flag
        String result = decryptor.decryptFromBase64(wireBase64, TEST_DEK, SAMPLE_AAD);
        assertThat(result).isEqualTo("valid");
    }

    // -----------------------------------------------------------------------
    // 5. GCM tag failure in decryptFromBase64 (no fallback — RULING 7a)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("RULING 7a — GCM tag fail in decryptFromBase64 throws SecurityException (no fallback)")
    void gcmTagFail_noFallback_throws() {
        byte[] envelope = FieldEnvelope.encrypt(
                "secret".getBytes(StandardCharsets.UTF_8), TEST_DEK, SAMPLE_AAD);
        // Tamper with tag byte
        envelope[envelope.length - 1] ^= 0x01;
        String wireBase64 = Base64.getEncoder().encodeToString(envelope);

        assertThatThrownBy(() -> decryptor.decryptFromBase64(wireBase64, TEST_DEK, SAMPLE_AAD))
                .isInstanceOf(SecurityException.class);
    }
}
