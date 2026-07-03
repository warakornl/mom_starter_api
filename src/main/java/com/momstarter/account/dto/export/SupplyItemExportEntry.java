package com.momstarter.account.dto.export;

import java.time.Instant;
import java.util.UUID;

/**
 * Supply item fields exported for PDPA ม.30/31.
 * Non-health personal data. Excludes {@code version} and {@code clientId}
 * (internal sync metadata not meaningful to the user).
 */
public record SupplyItemExportEntry(
        UUID id,
        String name,
        String category,
        String unit,
        int onHandQty,
        Integer lowThreshold,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
