package com.momstarter.account.dto.export;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Reminder definition fields exported for PDPA ม.30/31.
 * Health-adjacent personal data. Excludes {@code version} and {@code clientId}.
 */
public record ReminderExportEntry(
        UUID id,
        String type,
        String displayTitle,
        String sourceRefType,
        UUID sourceRefId,
        String recurrenceRule,
        LocalDateTime startAt,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
