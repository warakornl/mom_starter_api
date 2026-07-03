package com.momstarter.account.dto.export;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Medication log fields exported for PDPA ม.30/31 data portability (Slice 2 Task 5).
 *
 * <p>SD-2 health collection: dose-event records — {@code medication_log} table.
 * The {@link #noteCipher} bytea column is included verbatim (Jackson serialises {@code byte[]}
 * as Base64-encoded strings):
 * <ul>
 *   <li>Under the MVP no-op cipher posture (ADR RULING 1), the column holds PLAINTEXT
 *       bytes — the export is machine-readable and meaningful for PDPA ม.30 / ม.31.</li>
 *   <li>When real AES-GCM encryption lands, the client's decryption path makes it
 *       interpretable; the wire format is stable — zero contract change.</li>
 * </ul>
 *
 * <p>Tombstoned rows are included: {@link #noteCipher} will be {@code null}
 * (crypto-shredded per §4.4(A) / PDPA ruling 5a), while structural fields
 * ({@link #id}, {@link #status}, {@link #occurrenceTime}, {@link #loggedAt},
 * {@link #medicationPlanId}, {@link #deletedAt}) survive to serve as evidence that
 * the event was recorded and then deleted.
 *
 * <p>{@link #occurrenceTime} is a floating-civil timestamp (FLAG-1 / D5) — the calendar
 * bucket key the client uses for adherence computation. It is NEVER UTC-normalised.
 * {@link #loggedAt} is the server-assigned absolute-UTC creation instant (D5) — distinct
 * from {@link #occurrenceTime}.
 *
 * <p>Excludes: {@code userId} (implicit from the export envelope), {@code version} and
 * {@code clientId} (internal sync metadata not meaningful to the user).
 */
public record MedicationLogExportEntry(
        UUID id,
        UUID medicationPlanId,
        LocalDateTime occurrenceTime,
        String status,
        Instant loggedAt,
        byte[] noteCipher,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
