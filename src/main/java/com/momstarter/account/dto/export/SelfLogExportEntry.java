package com.momstarter.account.dto.export;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Self-log fields exported for PDPA ม.30/31 data portability (F3 fix, DEK-aware export).
 *
 * <h2>DEK-aware: cipher fields are decrypted readable strings (not raw bytes)</h2>
 * <p>Under Phase-1 DEK-aware export (ADR Decision 5), the server unwraps the account's
 * per-account DEK and applies the Decision-4 version dispatch before emitting the payload.
 * All four cipher value columns are emitted as decrypted {@code String} values — NOT raw
 * Base64-encoded bytes. The Decision-4 dispatch handles both:
 * <ul>
 *   <li>Legacy UNVERSIONED rows (base64-of-utf8-plaintext, no version byte) — decoded to
 *       readable plaintext without a DEK.</li>
 *   <li>Real 0x01-GCM envelopes — GCM-decrypted to readable plaintext with the account DEK.</li>
 * </ul>
 *
 * <h2>MOTHER-health collection (SD-5)</h2>
 * <p>weight · blood_pressure · swelling · lochia · symptom.
 * All four cipher value fields are included:
 * <ul>
 *   <li>{@link #valueNumeric} — primary numeric value (weight kg / BP systolic mmHg),
 *       decrypted to a readable string (e.g., {@code "72.5"}).</li>
 *   <li>{@link #valueNumericSecondary} — secondary numeric value (BP diastolic mmHg).
 *       {@code null} for non-blood_pressure metrics.</li>
 *   <li>{@link #valueText} — descriptive text value (swelling level / lochia / symptom).</li>
 *   <li>{@link #note} — optional free-text note (any metricType). Decrypted from
 *       {@code note_cipher}.</li>
 * </ul>
 *
 * <p>Tombstoned rows are included: cipher columns are {@code null} (crypto-shredded per
 * §4.4(A)); structural sync fields (id, metricType, loggedAt, deletedAt) survive.
 *
 * <p>Excludes: {@code userId} (implicit from the export envelope), {@code version} and
 * {@code clientId} (internal sync metadata not meaningful to the user).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SelfLogExportEntry(
        UUID id,
        String metricType,
        /**
         * Decrypted primary numeric value (e.g., weight in kg or BP systolic mmHg).
         * {@code null} for non-applicable metric types or on tombstoned rows (crypto-shredded).
         */
        String valueNumeric,
        /**
         * Decrypted secondary numeric value (e.g., BP diastolic mmHg).
         * {@code null} for non-blood_pressure metrics or on tombstoned rows.
         */
        String valueNumericSecondary,
        /**
         * Decrypted descriptive text value (e.g., swelling level, lochia description, symptom text).
         * {@code null} for weight/blood_pressure metrics or on tombstoned rows.
         */
        String valueText,
        /**
         * Decrypted free-text note. Decrypted from {@code self_log.note_cipher}.
         * {@code null} when no note was recorded or the row is tombstoned.
         */
        String note,
        String unit,
        LocalDateTime loggedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
