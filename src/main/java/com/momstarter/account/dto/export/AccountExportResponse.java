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
 *   <li>Auth tokens (refresh, email-verification, password-reset) — ephemeral credentials</li>
 *   <li>Raw cipher bytes — all {@code *_cipher} columns are DECRYPTED server-side before
 *       export (DEK-aware export, ADR Decision 5); the JSON carries readable plaintext strings,
 *       never opaque Base64 cipher bytes.</li>
 * </ul>
 *
 * <p>DEK-aware export (Phase-1 sub-slice): {@code AccountExportService} loads the account's
 * {@code account_dek} wrapped-DEK, unwraps it via {@code KmsClient.decryptDek}, and applies
 * the Decision-4 version dispatch on every cipher field. Legacy UNVERSIONED rows decode
 * gracefully without a DEK; real 0x01-GCM envelopes are AES-256-GCM decrypted. The DEK
 * is transient in memory and NEVER logged or emitted.
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
         * All expense records (live + tombstoned). Empty list when none exist.
         * Non-health personal-financial data (expenses-ui.md §0).
         */
        List<ExpenseExportEntry> expenses,

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
         *
         * <p>Each entry includes a {@code note} field (decrypted from {@code note_cipher}) when
         * a note was recorded — re-included per ADR IMPORTANT-5 / ม.30 completeness requirement.
         * {@code null} note means no note was set or the cipher was crypto-shredded (tombstone).
         */
        List<KickCountSessionExportEntry> kickCountSessions,

        /**
         * All self-log health records (live + tombstoned). Empty list when none exist.
         *
         * <p>MOTHER-health collection (SD-5): weight, blood_pressure, swelling, lochia, symptom.
         * All four cipher value fields are DEK-decrypted and emitted as readable {@code String}
         * values: {@code valueNumeric}, {@code valueNumericSecondary}, {@code valueText},
         * {@code note} (from {@code note_cipher}).
         * (F3 fix — PDPA ม.30 portability requires access to the actual health measurements.)
         *
         * <p>On tombstoned rows, the cipher columns are {@code null} (crypto-shredded per §4.4(A)
         * / PDPA ruling 5a); only structural sync columns survive.
         */
        List<SelfLogExportEntry> selfLogs,

        /**
         * All medication plans (live + tombstoned). Empty list when none exist.
         *
         * <p>SD-2 health collection: medication schedule records.
         * {@code name} (from {@code name_cipher}) and {@code dose} (from {@code dose_cipher})
         * are DEK-decrypted and emitted as readable strings (PDPA ม.30 access right).
         * {@code scheduleRule} is the FLAG-4 recurrence grammar (JSON string).
         *
         * <p>On tombstoned rows, {@code name}/{@code dose} are {@code null}
         * (crypto-shredded per §4.4(A)); structural fields survive.
         */
        List<MedicationPlanExportEntry> medicationPlans,

        /**
         * All medication logs / dose-event records (live + tombstoned). Empty list when none exist.
         *
         * <p>SD-2 health collection: taken/missed dose events.
         * {@code note} (from {@code note_cipher}) is DEK-decrypted and emitted as a readable
         * string (PDPA ม.30 portability).
         * {@code occurrenceTime} is floating-civil (FLAG-1); {@code loggedAt} is server-UTC (D5).
         *
         * <p>On tombstoned rows, {@code note} is {@code null} (crypto-shredded per §4.4(A));
         * structural fields survive.
         */
        List<MedicationLogExportEntry> medicationLogs,

        /**
         * Full append-only consent audit history (all rows). Empty list when none exist.
         * Ordered ascending by grantedAt so the history reads chronologically.
         */
        List<ConsentHistoryExportEntry> consentHistory
) {
}
