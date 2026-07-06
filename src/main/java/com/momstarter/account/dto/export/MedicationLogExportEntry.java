package com.momstarter.account.dto.export;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Medication log fields exported for PDPA ม.30/31 data portability (Slice 2 Task 5,
 * DEK-aware export).
 *
 * <h2>DEK-aware: note cipher is decrypted to a readable string</h2>
 * <p>Under Phase-1 DEK-aware export (ADR Decision 5), the server unwraps the account's
 * per-account DEK and applies the Decision-4 version dispatch. The dose-event note is
 * emitted as a decrypted {@code String} value — NOT raw Base64-encoded cipher bytes.
 *
 * <p>SD-2 health collection: taken/missed dose events — {@code medication_log} table.
 *
 * <h2>Timestamps</h2>
 * <p>{@link #occurrenceTime} is a floating-civil timestamp (FLAG-1 / D5) — the calendar
 * bucket key the client uses for adherence computation. It is NEVER UTC-normalised.
 * {@link #loggedAt} is the server-assigned absolute-UTC creation instant (D5) — distinct
 * from {@link #occurrenceTime}.
 *
 * <p>Tombstoned rows are included: {@link #note} will be {@code null}
 * (crypto-shredded per §4.4(A) / PDPA ruling 5a); structural fields ({@link #id},
 * {@link #status}, {@link #occurrenceTime}, {@link #loggedAt}, {@link #medicationPlanId},
 * {@link #deletedAt}) survive.
 *
 * <p>Excludes: {@code userId} (implicit from the export envelope), {@code version} and
 * {@code clientId} (internal sync metadata not meaningful to the user).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicationLogExportEntry(
        UUID id,
        UUID medicationPlanId,
        LocalDateTime occurrenceTime,
        String status,
        Instant loggedAt,
        /**
         * Decrypted free-text note for this dose event. Decrypted from
         * {@code medication_log.note_cipher}. {@code null} when no note was recorded,
         * or the row is tombstoned (crypto-shredded per §4.4(A)).
         */
        String note,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
