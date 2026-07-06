package com.momstarter.encryption;

import org.springframework.stereotype.Component;

/**
 * Global flag controlling whether legacy UNVERSIONED field envelopes are accepted.
 *
 * <h2>Background — appsec RULING 7b / M-2</h2>
 * <p>Legacy rows are stored as {@code base64(utf8(plaintext))} — no version byte,
 * no GCM envelope. During the migration window, the server must read both legacy
 * blobs and real GCM envelopes ({@link FieldEnvelope#VERSION_BYTE} = {@code 0x01}).
 *
 * <p>Per-record anti-downgrade is architecturally impossible because the version
 * byte lives <em>inside</em> the {@code bytea} column — there is no separate
 * cipher-version column in the database that the server could check without
 * decrypting the blob first. The only workable enforcement mechanism is a
 * <strong>global cutover flag</strong>:
 *
 * <ul>
 *   <li><b>Before cutover</b> ({@link #isCutoverComplete()} = {@code false}, the default):
 *       {@link FieldEnvelopeDecryptor#decryptFromBase64} accepts both legacy blobs
 *       and GCM envelopes. This is the safe migration window state.</li>
 *   <li><b>After cutover</b> ({@link #isCutoverComplete()} = {@code true}):
 *       any blob that does not parse as a GCM envelope (first byte {@code 0x01},
 *       decoded length &ge; 29) is immediately rejected with {@link SecurityException}.
 *       Once set, downgrade to the identity cipher is impossible system-wide.</li>
 * </ul>
 *
 * <h2>When to set the flag</h2>
 * <p>Set to {@code true} only after confirming that <em>every</em> {@code *_cipher}
 * column in every health table contains only real GCM envelopes — no legacy blobs
 * remain (see ADR Decision 4 IMPORTANT-6: launch on a fresh DB so no legacy blob
 * ever reaches production).
 *
 * <h2>Configuration</h2>
 * <p>In production, drive this flag from a config property (e.g., Spring
 * {@code @Value("${encryption.field.legacy-cutover-complete:false}")}) and inject
 * via the constructor. In tests, call {@link #setCutoverComplete(boolean)} directly.
 */
@Component
public class LegacyCutoverPolicy {

    /**
     * Volatile to ensure visibility across threads without synchronization.
     * In production this is set once at startup and never changed; {@code volatile}
     * is sufficient and avoids the overhead of full synchronization.
     */
    private volatile boolean cutoverComplete;

    /** Creates a policy with the cutover flag {@code false} (pre-cutover: accepts legacy). */
    public LegacyCutoverPolicy() {
        this(false);
    }

    /**
     * Creates a policy with an explicit initial cutover state.
     *
     * @param cutoverComplete {@code true} to start in post-cutover mode (legacy blobs rejected)
     */
    public LegacyCutoverPolicy(boolean cutoverComplete) {
        this.cutoverComplete = cutoverComplete;
    }

    /**
     * Returns {@code true} if the global cutover to AES-GCM is complete.
     * When {@code true}, {@link FieldEnvelopeDecryptor} rejects any legacy blob.
     */
    public boolean isCutoverComplete() {
        return cutoverComplete;
    }

    /**
     * Sets the cutover state.
     *
     * <p>In production: set via constructor from a config property.
     * In tests: call this directly to toggle the flag.
     *
     * @param cutoverComplete {@code true} to reject legacy blobs; {@code false} to allow them
     */
    public void setCutoverComplete(boolean cutoverComplete) {
        this.cutoverComplete = cutoverComplete;
    }
}
