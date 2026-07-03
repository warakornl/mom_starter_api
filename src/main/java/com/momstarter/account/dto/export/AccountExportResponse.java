package com.momstarter.account.dto.export;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Top-level response body for {@code GET /account/export} (PDPA ม.30 data access +
 * ม.31 data portability).
 *
 * <p>All personal data owned by the authenticated user is assembled here in one
 * machine-readable JSON document. Each domain is a separate key so consumers can
 * parse individual sections without re-processing the whole blob.
 *
 * <p>Secrets that are NEVER exported:
 * <ul>
 *   <li>{@code users.password_hash} — credential material</li>
 *   <li>{@code kick_count_session.note_cipher} — client-encrypted blob that cannot be
 *       decrypted without the per-account DEK; exporting cipher without key is useless
 *       and potentially reveals crypto metadata</li>
 *   <li>Auth tokens (refresh, email-verification, password-reset) — ephemeral credentials</li>
 * </ul>
 *
 * <p>Soft-deleted records (tombstones) ARE included: PDPA ม.30 covers all data the
 * controller holds on the user, including records in the pre-erasure window before the
 * GC scheduler runs.
 *
 * <p>{@code pregnancyProfile} is {@code null} when the user has no profile; Jackson's
 * {@code NON_NULL} include strategy omits it from the JSON rather than emitting
 * {@code "pregnancyProfile": null}, which is cleaner for portable consumption.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountExportResponse(

        /**
         * Server-authoritative instant at which the export was assembled.
         * ISO-8601 UTC string in the JSON wire format.
         */
        Instant exportedAt,

        /**
         * The user's account fields. Never null (the controller guards on active user).
         * Excludes: passwordHash, tokens, deletedAt (internal deletion marker).
         */
        AccountExportEntry account,

        /**
         * The user's pregnancy profile, or {@code null} when none exists.
         */
        PregnancyProfileExportEntry pregnancyProfile,

        /**
         * All supply items (live + tombstoned). Empty list when none exist.
         */
        List<SupplyItemExportEntry> supplyItems,

        /**
         * All reminder definitions (live + tombstoned). Empty list when none exist.
         */
        List<ReminderExportEntry> reminders,

        /**
         * All reminder occurrence records (live + tombstoned). Empty list when none exist.
         *
         * <p>Note: in MVP, only {@code done}/{@code snoozed} occurrences are pushed
         * to the server (W-A sparse table). The export reflects what the server holds.
         */
        List<ReminderOccurrenceExportEntry> reminderOccurrences,

        /**
         * All checklist items (live + tombstoned). Empty list when none exist.
         */
        List<ChecklistItemExportEntry> checklistItems,

        /**
         * All kick-count sessions (live + tombstoned). Empty list when none exist.
         *
         * <p>Size note: sessions accumulate daily during pregnancy (≈270 max for a full term),
         * so this array is naturally bounded for a single user.
         * {@code note_cipher} is NEVER included — see class Javadoc.
         */
        List<KickCountSessionExportEntry> kickCountSessions,

        /**
         * Full append-only consent audit history (all rows). Empty list when none exist.
         * Ordered ascending by grantedAt so the history reads chronologically.
         */
        List<ConsentHistoryExportEntry> consentHistory
) {
}
