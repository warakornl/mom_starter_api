package com.momstarter.account.dto.export;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Kick-count session fields exported for PDPA ม.30/31.
 *
 * <p>DELIBERATE OMISSION — {@code noteCipher} is NEVER included:
 * <ul>
 *   <li>It is an AES-GCM ciphertext encrypted with the per-account DEK.</li>
 *   <li>Exporting cipher bytes without the DEK provides no useful information
 *       to the user and leaks crypto metadata.</li>
 *   <li>The DEK itself must never leave the encryption layer (PDPA / secrets rule).</li>
 * </ul>
 * The note content is accessible to the user through the app's decryption path.
 * If a future "export with decrypted notes" feature is required, it must be designed
 * as a separate, DEK-aware export operation.
 *
 * <p>Also excludes {@code version} and {@code clientId} (internal sync metadata).
 */
public record KickCountSessionExportEntry(
        UUID id,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Integer durationSeconds,
        Integer movementCount,
        int targetCount,
        String status,
        Integer gestationalWeekAtStart,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
