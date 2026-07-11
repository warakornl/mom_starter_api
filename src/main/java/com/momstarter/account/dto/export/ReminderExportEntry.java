package com.momstarter.account.dto.export;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Reminder definition fields exported for PDPA ม.30/31.
 * Health-adjacent personal data. Excludes {@code version} and {@code clientId}.
 *
 * <h2>Pregnancy-loss reversible-tombstone provenance (LOSS-INV-4, review item (1))</h2>
 * <p>{@code survivesEnded}, {@code deactivatedBy}, {@code deactivatedAt} are included so an
 * exported reminder is transparent about WHY it is inactive: a reminder deactivated by the
 * pregnancy-loss sweep ({@code deactivatedBy="loss_event"}) is fully retained (not erased —
 * S3 hard gate) and distinguishable from a reminder the user deactivated/deleted themselves.
 * Export is never lifecycle-gated (LOSS-INV-8) — these fields are present regardless of the
 * owning profile's current {@code lifecycle}.
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
        /**
         * By-ITEM pregnancy-loss survival allow-list flag (AC-2.3/LOSS-INV-5). {@code true} =
         * this reminder is NOT pregnancy-progress and keeps firing across a loss.
         */
        boolean survivesEnded,
        /**
         * Reversible-tombstone provenance marker. {@code null} = never swept by the loss path;
         * {@code "loss_event"} = deactivated by {@code POST /pregnancy-profile/loss-event}
         * (functional-spec §6.1), cleared back to {@code null} by a subsequent {@code reopen}.
         */
        String deactivatedBy,
        /**
         * Absolute-UTC instant the loss-event sweep deactivated this reminder. {@code null}
         * unless {@link #deactivatedBy} is set.
         */
        Instant deactivatedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
