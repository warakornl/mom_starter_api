package com.momstarter.account.dto.export;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Checklist item fields exported for PDPA ม.30/31.
 * Health-adjacent personal data (ANC visits, appointments, tasks).
 * Excludes {@code version}, {@code clientId}, and {@code sourceSuggestionStateId}
 * (internal linkage not meaningful as standalone exported data).
 */
public record ChecklistItemExportEntry(
        UUID id,
        String category,
        String title,
        LocalDateTime scheduledAt,
        boolean done,
        Instant doneAt,
        String note,
        String source,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
