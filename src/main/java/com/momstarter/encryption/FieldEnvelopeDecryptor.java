package com.momstarter.encryption;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Server-side field-envelope decryptor for DEK-aware export and PDF egress (Scheme A).
 *
 * <p>Implements the <em>Decision-4 version dispatch</em> from
 * {@code docs/api-spec/adr/field-encryption-kms-dek.md} Decision 3.2 / IMPORTANT-3.
 * This is the JVM mirror of the client-side read-path dispatcher, used ONLY by:
 * <ul>
 *   <li>{@code AccountExportService} (next slice — not yet wired)</li>
 *   <li>PDF generation when {@code includeLab=true}</li>
 * </ul>
 *
 * <p><strong>Decision-4 version dispatch rule:</strong>
 * <ol>
 *   <li>Base64-decode the wire value to get raw bytes.</li>
 *   <li>If first byte == {@code 0x01} <em>and</em> decoded length &ge; {@value FieldEnvelope#MIN_ENVELOPE_LENGTH}
 *       → treat as GCM envelope and call {@link FieldEnvelope#decrypt}.</li>
 *   <li>Otherwise → treat as legacy UNVERSIONED identity blob:
 *       return {@code new String(decodedBytes, UTF_8)}.
 *       Legacy blobs are {@code base64(utf8(plaintext))} — no version byte, no GCM.
 *       The {@code 0x01} magic-byte heuristic is the discriminator; a real GCM
 *       envelope is always &ge; 29 bytes, so a short or non-{@code 0x01} blob is
 *       unambiguously legacy. A health-text value whose UTF-8 first byte is {@code 0x01}
 *       (ASCII SOH control character) is astronomically unlikely.</li>
 * </ol>
 *
 * <h2>Downgrade guard (appsec RULING 7b / M-2)</h2>
 * <p>Once migration is complete, set {@link LegacyCutoverPolicy#setCutoverComplete(boolean)}
 * to {@code true}. After cutover, any legacy blob is rejected with
 * {@link SecurityException} — this is the global anti-downgrade fence.
 * Per-record anti-downgrade is impossible (version byte lives inside {@code bytea};
 * there is no separate DB column to check). Only this global flag is workable.
 *
 * <h2>NEVER decrypt on the normal sync/read path</h2>
 * <p>This class is <em>only</em> for the two sanctioned egress points (export + PDF).
 * The invariant INV-S2 — "server does not decrypt on the normal read path" — must
 * not be broken by calling this class from any other context.
 *
 * <h2>Security invariants</h2>
 * <ul>
 *   <li>Tag failure → throw {@link SecurityException}; never return partial plaintext.</li>
 *   <li>DEK never logged — the {@code dek} parameter must not appear in logs or errors.</li>
 *   <li>Ciphertext never logged — not even on error.</li>
 * </ul>
 */
@Component
public class FieldEnvelopeDecryptor {

    private final LegacyCutoverPolicy cutoverPolicy;

    /**
     * Constructs the decryptor.
     *
     * @param cutoverPolicy governs whether legacy (unversioned) blobs are accepted
     *                      or rejected. Inject a {@link LegacyCutoverPolicy}
     *                      driven by a config property in production.
     */
    public FieldEnvelopeDecryptor(LegacyCutoverPolicy cutoverPolicy) {
        this.cutoverPolicy = cutoverPolicy;
    }

    /**
     * Decrypts a base64-encoded field value using Decision-4 version dispatch.
     *
     * <p>The dispatch logic:
     * <ol>
     *   <li>Base64-decode {@code wireBase64} → raw bytes.</li>
     *   <li>If first byte == {@code 0x01} AND length &ge; 29:
     *       GCM decrypt via {@link FieldEnvelope#decrypt}.</li>
     *   <li>Otherwise: legacy path →
     *       if cutover is complete, throw; else return {@code UTF-8(raw bytes)}.</li>
     * </ol>
     *
     * @param wireBase64 RFC-4648 padded base64 string from the {@code *_cipher} column
     *                   (the same format the client sends and the server stores in {@code bytea})
     * @param dek        256-bit (32-byte) plaintext DEK for this account —
     *                   NEVER log this parameter
     * @param aad        field ownership context, must match what the client used during encryption
     * @return the decrypted plaintext as a UTF-8 string
     * @throws SecurityException if the GCM tag verification fails, the version byte is
     *                           unrecognised, or the legacy path is attempted after cutover
     * @throws IllegalArgumentException if {@code wireBase64} is not valid base64
     */
    public String decryptFromBase64(String wireBase64, byte[] dek, FieldAad aad) {
        byte[] decoded = Base64.getDecoder().decode(wireBase64);

        // --- Decision-4 version dispatch ---
        // Heuristic: first byte == 0x01 AND decoded length >= 29 → GCM envelope.
        // A real GCM envelope is: version(1) + iv(12) + ct(>=0) + tag(16) = min 29 bytes.
        // Health text whose UTF-8 starts with 0x01 (ASCII SOH) is astronomically unlikely.
        boolean isGcmEnvelope = decoded.length >= FieldEnvelope.MIN_ENVELOPE_LENGTH
                && decoded[0] == FieldEnvelope.VERSION_BYTE;

        if (isGcmEnvelope) {
            // GCM path: FieldEnvelope.decrypt verifies the tag and throws on any failure.
            // Returns UTF-8 bytes → decode to String.
            byte[] plaintext = FieldEnvelope.decrypt(decoded, dek, aad);
            return new String(plaintext, StandardCharsets.UTF_8);
        }

        // --- Legacy UNVERSIONED identity path ---
        // Legacy blobs are base64(utf8(plaintext)) — no version byte, no GCM.
        // The decoded bytes are directly the UTF-8 plaintext.
        if (cutoverPolicy.isCutoverComplete()) {
            // Post-cutover: reject any blob that is not a GCM envelope.
            // This is the global anti-downgrade fence (appsec RULING 7b / M-2).
            throw new SecurityException(
                    "Legacy unversioned field envelope rejected: cutover to AES-256-GCM is complete. "
                    + "All encrypted field values must be GCM envelopes (version byte 0x01).");
        }

        // Pre-cutover migration window: accept legacy blob as plaintext.
        return new String(decoded, StandardCharsets.UTF_8);
    }
}
