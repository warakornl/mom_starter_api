package com.momstarter.account.dto.export;

import java.time.Instant;
import java.util.UUID;

/**
 * Account fields exported for PDPA ม.30/31.
 *
 * <p>Deliberately excludes:
 * <ul>
 *   <li>{@code passwordHash} — credential; never exported</li>
 *   <li>{@code deletedAt}   — internal deletion marker; not meaningful to the user</li>
 *   <li>{@code version}     — internal concurrency token; not personal data</li>
 * </ul>
 */
public record AccountExportEntry(
        UUID id,
        String email,
        String locale,
        String status,
        boolean emailVerified,
        Instant createdAt,
        Instant updatedAt
) {
}
