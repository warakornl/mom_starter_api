package com.momstarter.encryption;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM field-level encryption primitive (server-side JVM implementation).
 *
 * <p>Implements the LOCKED envelope format from
 * {@code docs/security/note-cipher-encryption-design.md} §1:
 * <pre>
 *   envelope = version(1B=0x01) || iv(12B) || ciphertext(NB) || tag(16B)
 * </pre>
 *
 * <p>This class is the <em>third impl</em> in the cross-platform parity matrix
 * (appsec RULING 8 / I-2): the device impl (react-native-quick-crypto /
 * BoringSSL) and Node impl (Node crypto module) must produce <em>byte-identical</em>
 * output for the same (DEK, IV, plaintext, AAD) combination. The golden vectors
 * committed in {@code src/test/resources/encryption/golden-vectors.json} prove this.
 *
 * <h2>Security invariants (NEVER violate)</h2>
 * <ul>
 *   <li><b>Fresh IV per write</b> — {@link #encrypt} generates a new 12-byte CSPRNG IV
 *       on every call. IV reuse under the same DEK destroys GCM confidentiality and
 *       allows an attacker to recover the keystream and forge tags (appsec RULING 1).</li>
 *   <li><b>AAD binding</b> — every encryption binds the ciphertext to its owner, collection,
 *       record, and field via the AAD. A cross-account/record/field copy causes tag failure
 *       (appsec RULING 2).</li>
 *   <li><b>No plaintext on failure</b> — {@link #decrypt} throws {@link SecurityException}
 *       on any integrity or version failure. It never returns plaintext, falls back to
 *       plaintext, or logs ciphertext (appsec RULING 7a).</li>
 *   <li><b>DEK never logged</b> — the {@code dek} parameter must never appear in logs,
 *       error messages, or stack traces (appsec RULING 4 / RULING 5).</li>
 * </ul>
 *
 * <p>Thread-safety: the static {@link SecureRandom} instance is thread-safe. All other
 * state is local to the method call. This class is safe for concurrent use.
 */
public final class FieldEnvelope {

    // -----------------------------------------------------------------------
    // Constants (LOCKED — changes require version bump to 0x02 + "v2:" AAD prefix)
    // -----------------------------------------------------------------------

    /** Envelope version byte — lock-stepped with AAD prefix {@code "v1:"} in {@link FieldAad}. */
    public static final byte VERSION_BYTE = 0x01;

    /** IV (nonce) length in bytes — 96-bit as required by AES-GCM spec. */
    private static final int IV_LENGTH_BYTES = 12;

    /** GCM authentication tag length in bits — 128-bit is mandatory (appsec RULING 7a). */
    private static final int TAG_LENGTH_BITS = 128;

    /**
     * Minimum valid envelope length (bytes):
     * 1 (version) + 12 (IV) + 0 (min ciphertext) + 16 (tag) = 29.
     */
    public static final int MIN_ENVELOPE_LENGTH = 1 + IV_LENGTH_BYTES + (TAG_LENGTH_BITS / 8);

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";

    /**
     * CSPRNG — {@link SecureRandom} is thread-safe; one shared instance is preferred
     * over per-call construction (avoids repeated seeding overhead).
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** AES-256 requires exactly 32 bytes (256 bits). */
    private static final int DEK_LENGTH_BYTES = 32;

    private FieldEnvelope() {
        // utility class — no instances
    }

    // -----------------------------------------------------------------------
    // Internal guards
    // -----------------------------------------------------------------------

    /**
     * Validates that {@code dek} is exactly 32 bytes, enforcing the AES-256-only invariant.
     *
     * <p>Without this guard, {@link SecretKeySpec} silently accepts 16/24/32-byte keys,
     * producing AES-128/192-GCM instead of AES-256-GCM — a silent security downgrade.
     *
     * @param dek the DEK bytes to validate
     * @throws SecurityException if {@code dek} is {@code null} or not exactly 32 bytes
     */
    private static void requireAes256Dek(byte[] dek) {
        if (dek == null || dek.length != DEK_LENGTH_BYTES) {
            throw new SecurityException("DEK must be 32 bytes (AES-256)");
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Encrypts {@code plaintext} with AES-256-GCM using a fresh CSPRNG IV.
     *
     * <p>The IV is generated with {@link SecureRandom} on every call — NEVER reused
     * (appsec RULING 1). Encrypting the same plaintext twice yields different
     * envelopes (different IVs → different ciphertexts).
     *
     * @param plaintext raw plaintext bytes to encrypt (may be empty; may be Thai UTF-8)
     * @param dek       256-bit (32-byte) AES key — NEVER log this parameter
     * @param aad       field ownership context bound into the GCM tag
     * @return envelope bytes: {@code 0x01 || iv(12) || ciphertext(N) || tag(16)}
     * @throws SecurityException if the JCE call fails (should not occur with valid 256-bit DEK)
     */
    public static byte[] encrypt(byte[] plaintext, byte[] dek, FieldAad aad) {
        requireAes256Dek(dek);
        byte[] iv = new byte[IV_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        return encryptWithIv(plaintext, dek, aad, iv);
    }

    /**
     * Decrypts a field envelope, verifying the GCM authentication tag.
     *
     * <p>The tag covers both the ciphertext and the AAD — so any bit-flip in
     * the ciphertext, tag, IV, or a mismatch in the AAD causes this method to throw.
     *
     * <p><strong>SECURITY — no fallback, no logging (appsec RULING 7a):</strong>
     * on any failure (wrong tag, wrong version, truncated envelope) this method
     * throws {@link SecurityException}. It never returns partial plaintext, falls
     * back to treating the bytes as plaintext, or logs the ciphertext or DEK.
     *
     * @param envelope ciphertext envelope: {@code 0x01 || iv(12) || ciphertext || tag(16)}
     * @param dek      256-bit (32-byte) AES key used during encryption — NEVER log this
     * @param aad      field ownership context — must match exactly what was used during encryption
     * @return decrypted plaintext bytes
     * @throws SecurityException if the envelope is malformed, the version byte is unknown,
     *                           the GCM tag verification fails, or the DEK/AAD are wrong
     */
    public static byte[] decrypt(byte[] envelope, byte[] dek, FieldAad aad) {
        requireAes256Dek(dek);
        if (envelope.length < MIN_ENVELOPE_LENGTH) {
            throw new SecurityException(
                    "Field envelope too short (" + envelope.length + " bytes; minimum " + MIN_ENVELOPE_LENGTH + ")");
        }
        if (envelope[0] != VERSION_BYTE) {
            throw new SecurityException(
                    "Unknown field envelope version byte (expected 0x01)");
        }

        byte[] iv = Arrays.copyOfRange(envelope, 1, 1 + IV_LENGTH_BYTES);
        // ctAndTag spans from after version+IV to end of envelope; JCE handles tag split
        byte[] ctAndTag = Arrays.copyOfRange(envelope, 1 + IV_LENGTH_BYTES, envelope.length);

        try {
            SecretKeySpec keySpec = new SecretKeySpec(dek, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            cipher.updateAAD(aad.toAadBytes());
            // doFinal verifies the tag atomically before returning any plaintext.
            // If the tag does not match, AEADBadTagException is thrown here.
            return cipher.doFinal(ctAndTag);
        } catch (AEADBadTagException e) {
            // Tag failure: ciphertext/tag was tampered, DEK is wrong, or AAD is wrong.
            // NEVER log ciphertext or the DEK (appsec RULING 7a).
            throw new SecurityException("Field envelope authentication failed — data may be tampered or AAD mismatch");
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Field envelope decryption failed");
        }
    }

    // -----------------------------------------------------------------------
    // Package-private: test-only fixed-IV variant
    // -----------------------------------------------------------------------

    /**
     * Encrypts using a caller-supplied IV instead of a CSPRNG-generated one.
     *
     * <p><strong>FOR TESTING ONLY</strong> — used by golden-vector tests to produce
     * deterministic output for cross-impl verification. Production code MUST use
     * {@link #encrypt} (which generates a fresh CSPRNG IV on every call).
     *
     * @param plaintext raw plaintext bytes
     * @param dek       256-bit AES key — NEVER log
     * @param aad       field ownership context
     * @param iv        caller-supplied 12-byte IV (MUST be unique per (DEK, IV) pair)
     * @return envelope bytes: {@code 0x01 || iv(12) || ciphertext(N) || tag(16)}
     */
    static byte[] encryptWithIv(byte[] plaintext, byte[] dek, FieldAad aad, byte[] iv) {
        requireAes256Dek(dek);
        if (iv.length != IV_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "IV must be exactly " + IV_LENGTH_BYTES + " bytes, got " + iv.length);
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(dek, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            cipher.updateAAD(aad.toAadBytes());
            // JCE returns: ciphertext || tag (tag is always TAG_LENGTH_BITS/8 = 16 bytes)
            byte[] ctAndTag = cipher.doFinal(plaintext);

            // Build envelope: version(1) || iv(12) || ciphertext+tag(N+16)
            byte[] envelope = new byte[1 + IV_LENGTH_BYTES + ctAndTag.length];
            envelope[0] = VERSION_BYTE;
            System.arraycopy(iv, 0, envelope, 1, IV_LENGTH_BYTES);
            System.arraycopy(ctAndTag, 0, envelope, 1 + IV_LENGTH_BYTES, ctAndTag.length);
            return envelope;
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Field envelope encryption failed");
        }
    }
}
