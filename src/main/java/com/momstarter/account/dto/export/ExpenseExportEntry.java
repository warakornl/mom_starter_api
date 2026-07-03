package com.momstarter.account.dto.export;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Expense fields exported for PDPA ม.30/31 data portability.
 *
 * <p>Non-health personal-financial data. Includes all user-facing fields.
 * Excludes {@code version} and {@code clientId} (internal sync metadata not meaningful
 * to the user).
 *
 * <p>Note: architecture gap flagged — expenses-ui.md §8 (EX-1/EX-2) describes
 * {@code amount} and {@code note} as client-encrypted, but this implementation stores
 * both as plaintext at-rest under KMS volume encryption. If field-level encryption is
 * confirmed, these fields would be cipher blobs and the export would need to handle
 * decryptable vs. opaque representation (like {@code kick_count_session.note_cipher}
 * which is excluded from the export). Architect must confirm before production.
 */
public record ExpenseExportEntry(
        UUID id,
        int amount,
        String category,
        LocalDate incurredOn,
        String note,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
