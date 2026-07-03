package com.momstarter.account;

import com.momstarter.account.dto.export.AccountExportEntry;
import com.momstarter.account.dto.export.AccountExportResponse;
import com.momstarter.account.dto.export.ChecklistItemExportEntry;
import com.momstarter.account.dto.export.ConsentHistoryExportEntry;
import com.momstarter.account.dto.export.ExpenseExportEntry;
import com.momstarter.account.dto.export.KickCountSessionExportEntry;
import com.momstarter.account.dto.export.MedicationLogExportEntry;
import com.momstarter.account.dto.export.MedicationPlanExportEntry;
import com.momstarter.account.dto.export.PregnancyProfileExportEntry;
import com.momstarter.account.dto.export.ReminderExportEntry;
import com.momstarter.account.dto.export.ReminderOccurrenceExportEntry;
import com.momstarter.account.dto.export.SelfLogExportEntry;
import com.momstarter.account.dto.export.SupplyItemExportEntry;
import com.momstarter.checklist.ChecklistItemRepository;
import com.momstarter.consent.ConsentRecordRepository;
import com.momstarter.expense.ExpenseRepository;
import com.momstarter.error.ApiException;
import com.momstarter.kickcount.KickCountSessionRepository;
import com.momstarter.medication.MedicationLogRepository;
import com.momstarter.medication.MedicationPlanRepository;
import com.momstarter.pregnancy.PregnancyProfile;
import com.momstarter.pregnancy.PregnancyProfileRepository;
import com.momstarter.reminder.ReminderOccurrenceRepository;
import com.momstarter.reminder.ReminderRepository;
import com.momstarter.selflog.SelfLogRepository;
import com.momstarter.supply.SupplyItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Assembles the PDPA ม.30/31 data-portability export for the authenticated user.
 *
 * <h2>What is exported</h2>
 * <p>All personal data rows owned by the user across every domain:
 * <ul>
 *   <li>Account — email, locale, status, emailVerified, createdAt, updatedAt</li>
 *   <li>PregnancyProfile — all health fields including EDD, lifecycle, birth event</li>
 *   <li>SupplyItems — all rows (live + tombstoned)</li>
 *   <li>Reminders — all rows (live + tombstoned)</li>
 *   <li>ReminderOccurrences — all rows (live + tombstoned)</li>
 *   <li>ChecklistItems — all rows (live + tombstoned)</li>
 *   <li>KickCountSessions — all rows (live + tombstoned); {@code noteCipher} excluded</li>
 *   <li>SelfLogs — all rows (live + tombstoned); all bytea value columns included (F3 fix)</li>
 *   <li>MedicationPlans — all rows (live + tombstoned); nameCipher/doseCipher/scheduleRule included</li>
 *   <li>MedicationLogs — all rows (live + tombstoned); noteCipher included</li>
 *   <li>ConsentHistory — full append-only audit log, chronological</li>
 * </ul>
 *
 * <h2>What is NEVER exported</h2>
 * <ul>
 *   <li>{@code users.password_hash} — credential material</li>
 *   <li>{@code kick_count_session.note_cipher} — AES-GCM cipher blob (useless without DEK)</li>
 *   <li>Auth tokens (refresh, email-verification, password-reset) — ephemeral credentials</li>
 *   <li>Internal concurrency tokens ({@code version}), client device ids ({@code clientId})</li>
 * </ul>
 *
 * <h2>Soft-deleted accounts</h2>
 * <p>Returns {@code 404 not_found} for soft-deleted accounts (consistent with
 * {@code GET /account}). PDPA ม.30/31 export is available pre-deletion; once
 * {@code DELETE /account} is called the 15-minute access-token window allows
 * {@code GET /account} to return 404, and this endpoint follows the same guard.
 *
 * <h2>Logging</h2>
 * <p>No health fields are logged. The method logs only the authenticated user's id and
 * the timestamp of the export — never email, EDD, movement counts, or note content.
 */
@Service
public class AccountExportService {

    private static final Logger log = LoggerFactory.getLogger(AccountExportService.class);

    private final UserRepository users;
    private final PregnancyProfileRepository profiles;
    private final SupplyItemRepository supplyItems;
    private final ExpenseRepository expenseItems;
    private final ReminderRepository reminders;
    private final ReminderOccurrenceRepository reminderOccurrences;
    private final ChecklistItemRepository checklistItems;
    private final KickCountSessionRepository kickCountSessions;
    private final SelfLogRepository selfLogs;
    private final MedicationPlanRepository medicationPlans;
    private final MedicationLogRepository medicationLogs;
    private final ConsentRecordRepository consentRecords;

    public AccountExportService(
            UserRepository users,
            PregnancyProfileRepository profiles,
            SupplyItemRepository supplyItems,
            ExpenseRepository expenseItems,
            ReminderRepository reminders,
            ReminderOccurrenceRepository reminderOccurrences,
            ChecklistItemRepository checklistItems,
            KickCountSessionRepository kickCountSessions,
            SelfLogRepository selfLogs,
            MedicationPlanRepository medicationPlans,
            MedicationLogRepository medicationLogs,
            ConsentRecordRepository consentRecords) {
        this.users = users;
        this.profiles = profiles;
        this.supplyItems = supplyItems;
        this.expenseItems = expenseItems;
        this.reminders = reminders;
        this.reminderOccurrences = reminderOccurrences;
        this.checklistItems = checklistItems;
        this.kickCountSessions = kickCountSessions;
        this.selfLogs = selfLogs;
        this.medicationPlans = medicationPlans;
        this.medicationLogs = medicationLogs;
        this.consentRecords = consentRecords;
    }

    /**
     * Assembles the full personal-data export for {@code userId}.
     *
     * <p>Auth: the controller extracts {@code userId} from the JWT subject exclusively —
     * no caller-supplied id is accepted (IDOR prevention). This service trusts the
     * injected {@code userId} and scopes every query by it.
     *
     * @param userId JWT subject (the requesting user's id)
     * @return complete export response
     * @throws ApiException 404 {@code not_found} if the user is absent or soft-deleted
     */
    @Transactional(readOnly = true)
    public AccountExportResponse export(UUID userId) {
        // Guard: active user only — consistent with GET /account behavior.
        // Soft-deleted accounts return 404 (see class Javadoc for rationale).
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(404, "not_found"));
        if (user.getDeletedAt() != null) {
            throw new ApiException(404, "not_found");
        }

        // Log export event: user id + timestamp only; no health fields (PDPA / no-PII-in-logs).
        Instant exportedAt = Instant.now();
        log.info("PDPA export assembled for user={} at={}", userId, exportedAt);

        // ---- account ----
        AccountExportEntry accountEntry = new AccountExportEntry(
                user.getId(),
                user.getEmail(),
                user.getLocale(),
                user.getStatus(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.getUpdatedAt()
                // passwordHash: NEVER exported
        );

        // ---- pregnancy profile ----
        PregnancyProfileExportEntry profileEntry = profiles.findByUserId(userId)
                .map(this::toProfileEntry)
                .orElse(null);

        // ---- supply items ----
        List<SupplyItemExportEntry> supplyItemEntries = supplyItems
                .findAllByUserIdForExport(userId)
                .stream()
                .map(s -> new SupplyItemExportEntry(
                        s.getId(), s.getName(), s.getCategory(), s.getUnit(),
                        s.getOnHandQty(), s.getLowThreshold(),
                        s.getCreatedAt(), s.getUpdatedAt(), s.getDeletedAt()))
                .toList();

        // ---- expenses ----
        // Non-health personal-financial data (PDPA ม.30: user's right to access their data).
        // Note: if amount/note encryption is confirmed (EX-1/EX-2), cipher fields would be
        // excluded (like kick_count_session.note_cipher). Currently stored plaintext.
        List<ExpenseExportEntry> expenseEntries = expenseItems
                .findAllByUserIdForExport(userId)
                .stream()
                .map(e -> new ExpenseExportEntry(
                        e.getId(), e.getAmount(), e.getCategory(), e.getIncurredOn(),
                        e.getNote(), e.getCreatedAt(), e.getUpdatedAt(), e.getDeletedAt()))
                .toList();

        // ---- reminders ----
        List<ReminderExportEntry> reminderEntries = reminders
                .findAllByUserIdForExport(userId)
                .stream()
                .map(r -> new ReminderExportEntry(
                        r.getId(), r.getType(), r.getDisplayTitle(),
                        r.getSourceRefType(), r.getSourceRefId(),
                        r.getRecurrenceRule(), r.getStartAt(), r.isActive(),
                        r.getCreatedAt(), r.getUpdatedAt(), r.getDeletedAt()))
                .toList();

        // ---- reminder occurrences ----
        List<ReminderOccurrenceExportEntry> occurrenceEntries = reminderOccurrences
                .findAllByUserIdForExport(userId)
                .stream()
                .map(o -> new ReminderOccurrenceExportEntry(
                        o.getId(), o.getReminderId(), o.getScheduledLocalTime(),
                        o.getStatus(), o.getActedAt(), o.getSnoozedUntil(),
                        o.getCreatedAt(), o.getUpdatedAt(), o.getDeletedAt()))
                .toList();

        // ---- checklist items ----
        List<ChecklistItemExportEntry> checklistEntries = checklistItems
                .findAllByUserIdForExport(userId)
                .stream()
                .map(c -> new ChecklistItemExportEntry(
                        c.getId(), c.getCategory(), c.getTitle(), c.getScheduledAt(),
                        c.isDone(), c.getDoneAt(), c.getNote(), c.getSource(),
                        c.getCreatedAt(), c.getUpdatedAt(), c.getDeletedAt()))
                .toList();

        // ---- kick-count sessions ----
        // noteCipher is intentionally NOT mapped — see KickCountSessionExportEntry Javadoc.
        List<KickCountSessionExportEntry> sessionEntries = kickCountSessions
                .findAllByUserIdForExport(userId)
                .stream()
                .map(s -> new KickCountSessionExportEntry(
                        s.getId(), s.getStartedAt(), s.getEndedAt(),
                        s.getDurationSeconds(), s.getMovementCount(),
                        s.getTargetCount(), s.getStatus(), s.getGestationalWeekAtStart(),
                        s.getCreatedAt(), s.getUpdatedAt(), s.getDeletedAt()))
                .toList();

        // ---- self-logs ---- (F3 fix: PDPA ม.30/31 portability — health values included)
        // All four bytea value columns are mapped verbatim (Base64 on the wire via Jackson).
        // Under the MVP no-op cipher, values are plaintext-readable. On tombstoned rows,
        // the bytea columns are null (crypto-shredded per §4.4(A) / PDPA ruling 5a).
        // No health data is logged — only user id and export timestamp (no-PII-in-logs rule).
        List<SelfLogExportEntry> selfLogEntries = selfLogs
                .findAllByUserIdForExport(userId)
                .stream()
                .map(s -> new SelfLogExportEntry(
                        s.getId(), s.getMetricType(),
                        s.getValueNumeric(), s.getValueNumericSecondary(),
                        s.getValueText(), s.getNoteCipher(),
                        s.getUnit(), s.getLoggedAt(),
                        s.getCreatedAt(), s.getUpdatedAt(), s.getDeletedAt()))
                .toList();

        // ---- medication plans ---- (Task 5: SD-2 health data — PDPA ม.30/31 portability)
        // nameCipher and doseCipher are mapped verbatim (Base64 on the wire via Jackson).
        // scheduleRule (FLAG-4 JSON) is included: the user's medication schedule is their data.
        // On tombstoned rows, nameCipher and doseCipher are null (crypto-shredded per §4.4(A)).
        // No health data is logged (no-PII-in-logs rule).
        List<MedicationPlanExportEntry> medicationPlanEntries = medicationPlans
                .findAllByUserIdForExport(userId)
                .stream()
                .map(p -> new MedicationPlanExportEntry(
                        p.getId(), p.getNameCipher(), p.getDoseCipher(),
                        p.getScheduleRule(), p.isActive(), p.getSourceSuggestionStateId(),
                        p.getCreatedAt(), p.getUpdatedAt(), p.getDeletedAt()))
                .toList();

        // ---- medication logs ---- (Task 5: SD-2 health data — PDPA ม.30/31 portability)
        // noteCipher is mapped verbatim (Base64 on the wire via Jackson).
        // occurrenceTime is floating-civil (FLAG-1); loggedAt is server-UTC (D5).
        // On tombstoned rows, noteCipher is null (crypto-shredded per §4.4(A)).
        List<MedicationLogExportEntry> medicationLogEntries = medicationLogs
                .findAllByUserIdForExport(userId)
                .stream()
                .map(l -> new MedicationLogExportEntry(
                        l.getId(), l.getMedicationPlanId(),
                        l.getOccurrenceTime(), l.getStatus(), l.getLoggedAt(),
                        l.getNoteCipher(),
                        l.getCreatedAt(), l.getUpdatedAt(), l.getDeletedAt()))
                .toList();

        // ---- consent history ----
        List<ConsentHistoryExportEntry> consentEntries = consentRecords
                .findAllByUserIdForExport(userId)
                .stream()
                .map(c -> new ConsentHistoryExportEntry(
                        c.getId(), c.getConsentType(), c.isGranted(),
                        c.getConsentTextVersion(), c.getLocale(),
                        c.getGrantedAt(), c.getCreatedAt()))
                .toList();

        return new AccountExportResponse(
                exportedAt,
                accountEntry,
                profileEntry,
                supplyItemEntries,
                expenseEntries,
                reminderEntries,
                occurrenceEntries,
                checklistEntries,
                sessionEntries,
                selfLogEntries,
                medicationPlanEntries,
                medicationLogEntries,
                consentEntries);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private PregnancyProfileExportEntry toProfileEntry(PregnancyProfile p) {
        return new PregnancyProfileExportEntry(
                p.getId(), p.getEdd(), p.getEddBasis(), p.getLifecycle(),
                p.getBirthDate(), p.getDeliveryType(), p.getBirthNote(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getDeletedAt());
    }
}
