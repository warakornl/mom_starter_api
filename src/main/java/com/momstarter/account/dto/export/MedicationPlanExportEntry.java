package com.momstarter.account.dto.export;

import java.time.Instant;
import java.util.UUID;

/**
 * Medication plan fields exported for PDPA ม.30/31 data portability (Slice 2 Task 5).
 *
 * <p>SD-2 health collection: medication schedule — {@code medication_plan} table.
 * Both bytea cipher columns are included verbatim (Jackson serialises {@code byte[]}
 * as Base64-encoded strings):
 * <ul>
 *   <li>Under the MVP no-op cipher posture (ADR RULING 1), these columns hold PLAINTEXT
 *       bytes — the export is machine-readable and meaningful for PDPA ม.30 data access +
 *       ม.31 portability.</li>
 *   <li>When real AES-GCM encryption lands (deferred KMS/EAS milestone), the client's
 *       decryption path makes the bytes interpretable; the wire format is stable
 *       (Base64 bytes in the same fields — zero contract change).</li>
 * </ul>
 *
 * <p>Tombstoned rows are included: {@link #nameCipher} and {@link #doseCipher} will be
 * {@code null} (crypto-shredded per §4.4(A) / PDPA ruling 5a), while structural sync
 * fields ({@link #id}, {@link #scheduleRule}, {@link #active}, {@link #deletedAt})
 * survive to serve as evidence that the record existed and was deleted.
 *
 * <p>{@link #scheduleRule} is the FLAG-4 recurrence grammar stored as a JSON string
 * (jsonb in PostgreSQL). Exported verbatim — the user's medication schedule is personal
 * data subject to ม.30/31 portability.
 *
 * <p>Excludes: {@code userId} (implicit from the export envelope), {@code version} and
 * {@code clientId} (internal sync metadata not meaningful to the user).
 */
public record MedicationPlanExportEntry(
        UUID id,
        byte[] nameCipher,
        byte[] doseCipher,
        String scheduleRule,
        boolean active,
        UUID sourceSuggestionStateId,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
