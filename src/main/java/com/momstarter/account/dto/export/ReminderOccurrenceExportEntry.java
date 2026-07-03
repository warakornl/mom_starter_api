package com.momstarter.account.dto.export;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Reminder occurrence fields exported for PDPA ม.30/31.
 * Represents adherence history (done/snoozed actions). Excludes {@code version}
 * and {@code clientId} (internal sync metadata).
 */
public record ReminderOccurrenceExportEntry(
        UUID id,
        UUID reminderId,
        LocalDateTime scheduledLocalTime,
        String status,
        Instant actedAt,
        Instant snoozedUntil,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
