package com.momstarter.account.dto.export;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Self-log fields exported for PDPA ม.30/31 data portability (F3 fix).
 *
 * <p>MOTHER-health collection (SD-5): weight, blood_pressure, swelling, lochia, symptom.
 * All four bytea value columns are included verbatim (Jackson serialises {@code byte[]}
 * as Base64-encoded strings):
 * <ul>
 *   <li>Under the MVP "no-op cipher" posture (ADR self-log-encryption-posture Decision 1),
 *       these columns hold PLAINTEXT bytes — the export is machine-readable and meaningful
 *       for PDPA ม.30 data access + ม.31 portability (PDPA ruling 2.2).</li>
 *   <li>When real AES-GCM encryption lands (deferred KMS/EAS milestone), the client's
 *       decryption path makes the bytes interpretable; the wire format is stable
 *       (Base64 bytes in the same fields — zero contract change).</li>
 * </ul>
 *
 * <p>Unlike {@code kick_count_session.note_cipher} (excluded from that export because it is
 * designed as a DEK-encrypted blob), {@code self_log} bytea columns are deliberately INCLUDED
 * here per compliance ruling F3: the health measurements (weight, BP, swelling/lochia/symptom
 * text, free-text note) are the core personal data the user has a right to access and port.
 *
 * <p>Tombstoned rows are included: bytea value columns will be {@code null} (crypto-shredded
 * per §4.4(A)), while structural sync fields (id, metricType, loggedAt, deletedAt) survive
 * to serve as evidence that the record existed and was deleted.
 *
 * <p>Excludes: {@code userId} (implicit from the export envelope), {@code version} and
 * {@code clientId} (internal sync metadata not meaningful to the user).
 */
public record SelfLogExportEntry(
        UUID id,
        String metricType,
        byte[] valueNumeric,
        byte[] valueNumericSecondary,
        byte[] valueText,
        byte[] noteCipher,
        String unit,
        LocalDateTime loggedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
