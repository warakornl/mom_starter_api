package com.momstarter.account.dto.export;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Medication plan fields exported for PDPA ม.30/31 data portability (Slice 2 Task 5,
 * DEK-aware export).
 *
 * <h2>DEK-aware: cipher fields are decrypted readable strings (not raw bytes)</h2>
 * <p>Under Phase-1 DEK-aware export (ADR Decision 5), the server unwraps the account's
 * per-account DEK and applies the Decision-4 version dispatch. The medication name and dose
 * are emitted as decrypted {@code String} values — NOT raw Base64-encoded cipher bytes.
 *
 * <p>SD-2 health collection: medication schedule — {@code medication_plan} table.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@link #name} — decrypted medication name (from {@code name_cipher}).
 *       {@code null} on tombstoned rows (crypto-shredded §4.4(A)).</li>
 *   <li>{@link #dose} — decrypted dosage description (from {@code dose_cipher}).
 *       Genuinely optional (nullable) — not all plans have an explicit dose.</li>
 *   <li>{@link #scheduleRule} — FLAG-4 recurrence grammar (jsonb). Exported verbatim —
 *       the user's medication schedule is personal data subject to ม.30/31 portability.</li>
 * </ul>
 *
 * <p>Tombstoned rows are included: {@link #name} and {@link #dose} will be {@code null}
 * (crypto-shredded per §4.4(A) / PDPA ruling 5a); structural sync fields ({@link #id},
 * {@link #scheduleRule}, {@link #active}, {@link #deletedAt}) survive.
 *
 * <p>Excludes: {@code userId} (implicit from the export envelope), {@code version} and
 * {@code clientId} (internal sync metadata not meaningful to the user).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicationPlanExportEntry(
        UUID id,
        /**
         * Decrypted medication name. Decrypted from {@code medication_plan.name_cipher}.
         * {@code null} on tombstoned rows (crypto-shredded per §4.4(A)).
         */
        String name,
        /**
         * Decrypted dosage description. Decrypted from {@code medication_plan.dose_cipher}.
         * {@code null} when not specified or on tombstoned rows.
         */
        String dose,
        String scheduleRule,
        boolean active,
        UUID sourceSuggestionStateId,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
