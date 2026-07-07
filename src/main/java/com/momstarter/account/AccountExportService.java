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
import com.momstarter.encryption.FieldAad;
import com.momstarter.encryption.FieldEnvelopeDecryptor;
import com.momstarter.encryption.KmsClient;
import com.momstarter.error.ApiException;
import com.momstarter.expense.ExpenseRepository;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Assembles the PDPA ม.30/31 data-portability export for the authenticated user.
 *
 * <h2>Phase-1 DEK-aware export (ADR Decision 5)</h2>
 * <p>All health cipher fields are <em>server-side decrypted</em> before export. The export
 * payload contains readable plaintext strings (not raw Base64 cipher bytes). The
 * Decision-4 version dispatch handles both:
 * <ul>
 *   <li><b>Legacy UNVERSIONED rows</b> (base64-of-utf8-plaintext, first byte ≠ {@code 0x01}
 *       or length &lt; 29): decoded to readable plaintext via the legacy identity path —
 *       no DEK required.</li>
 *   <li><b>Real 0x01-GCM envelopes</b> (first byte = {@code 0x01}, length ≥ 29): AES-256-GCM
 *       decrypted with the account's plaintext DEK (unwrapped from {@code account_dek} via
 *       {@link KmsClient#decryptDek}).</li>
 * </ul>
 *
 * <h2>DEK handling</h2>
 * <ol>
 *   <li>Load the {@code account_dek} row via {@link AccountDekRepository}.</li>
 *   <li>If present: unwrap to plaintext DEK via {@code kmsClient.decryptDek(wrappedDek, accountId)}.</li>
 *   <li>If absent (legacy/pre-provisioned account): DEK = {@code null}. The legacy dispatch
 *       path does not use the DEK, so pre-provision accounts with legacy rows export gracefully.</li>
 * </ol>
 * <p>The plaintext DEK is transient in memory ONLY. It is NEVER logged, persisted,
 * or returned in any error body (PDPA / appsec RULING 4/5).
 *
 * <h2>AAD frozen logical names (RULING 2 — name-stability)</h2>
 * <p>The AAD for each field uses FROZEN logical identifiers (see constants below).
 * These are baked into every GCM tag on write and MUST NOT be changed without bumping
 * the envelope version and re-encrypting all rows. They are NOT the wire/JSON field names.
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
 *   <li>KickCountSessions — all rows (live + tombstoned); {@code note} = decrypted note_cipher
 *       (re-included per ADR IMPORTANT-5 / ม.30 completeness)</li>
 *   <li>SelfLogs — all rows (live + tombstoned); all cipher value fields decrypted to strings</li>
 *   <li>MedicationPlans — all rows (live + tombstoned); {@code name}/{@code dose} decrypted</li>
 *   <li>MedicationLogs — all rows (live + tombstoned); {@code note} decrypted</li>
 *   <li>ConsentHistory — full append-only audit log, chronological</li>
 * </ul>
 *
 * <h2>What is NEVER exported</h2>
 * <ul>
 *   <li>{@code users.password_hash} — credential material</li>
 *   <li>Raw cipher bytes — all {@code *_cipher} columns are decrypted before export</li>
 *   <li>The plaintext DEK — transient in memory only, NEVER logged or emitted</li>
 *   <li>Auth tokens (refresh, email-verification, password-reset) — ephemeral credentials</li>
 *   <li>Internal concurrency tokens ({@code version}), client device ids ({@code clientId})</li>
 * </ul>
 *
 * <h2>Soft-deleted accounts</h2>
 * <p>Returns {@code 404 not_found} for soft-deleted accounts (consistent with
 * {@code GET /account}). PDPA ม.30/31 export is available pre-deletion.
 *
 * <h2>Logging</h2>
 * <p>No health fields, DEK material, or cipher content is logged. The method logs only the
 * authenticated user's id and the timestamp of the export (no-PII-in-logs rule).
 */
@Service
public class AccountExportService {

    private static final Logger log = LoggerFactory.getLogger(AccountExportService.class);

    // -------------------------------------------------------------------------
    // AAD frozen logical identifiers (RULING 2 — name-stability)
    // NEVER rename these constants even if DB column names or wire JSON names change.
    // These identifiers are baked into every GCM tag and must match what the mobile
    // client uses when it encrypts. Changing them without a version bump silently breaks
    // every existing ciphertext (tag rejection = health data loss with no error).
    // -------------------------------------------------------------------------

    /** Frozen collection name for {@code self_log} cipher fields. */
    private static final String COLL_SELF_LOG = "selfLog";
    /** Frozen field name for {@code self_log.value_numeric}. */
    private static final String FIELD_SELF_LOG_VALUE_NUMERIC = "valueNumeric";
    /** Frozen field name for {@code self_log.value_numeric_secondary}. */
    private static final String FIELD_SELF_LOG_VALUE_NUMERIC_SECONDARY = "valueNumericSecondary";
    /** Frozen field name for {@code self_log.value_text}. */
    private static final String FIELD_SELF_LOG_VALUE_TEXT = "valueText";
    /** Frozen field name for {@code self_log.note_cipher}. */
    private static final String FIELD_SELF_LOG_NOTE = "note";

    /** Frozen collection name for {@code medication_plan} cipher fields. */
    private static final String COLL_MEDICATION_PLAN = "medicationPlan";
    /** Frozen field name for {@code medication_plan.name_cipher}. */
    private static final String FIELD_MED_PLAN_NAME = "name";
    /** Frozen field name for {@code medication_plan.dose_cipher}. */
    private static final String FIELD_MED_PLAN_DOSE = "dose";

    /** Frozen collection name for {@code medication_log} cipher fields. */
    private static final String COLL_MEDICATION_LOG = "medicationLog";
    /** Frozen field name for {@code medication_log.note_cipher}. */
    private static final String FIELD_MED_LOG_NOTE = "note";

    /** Frozen collection name for {@code kick_count_session} cipher fields. */
    private static final String COLL_KICK_COUNT_SESSION = "kickCountSession";
    /** Frozen field name for {@code kick_count_session.note_cipher}. */
    private static final String FIELD_KICK_NOTE = "note";

    /**
     * Frozen collection name for {@code pregnancy_profile} cipher fields (RULING 2a — name-stability).
     * This is a row-per-account table; AAD recordId = accountId (RULING 2b).
     * NEVER rename: baked into every GCM tag written by the mobile client.
     */
    private static final String COLL_PREGNANCY_PROFILE = "pregnancyProfile";
    /** Frozen field name for {@code pregnancy_profile.mother_first_name_cipher}. */
    private static final String FIELD_PP_MOTHER_FIRST_NAME = "motherFirstName";
    /** Frozen field name for {@code pregnancy_profile.mother_last_name_cipher}. */
    private static final String FIELD_PP_MOTHER_LAST_NAME = "motherLastName";
    /** Frozen field name for {@code pregnancy_profile.baby_name_cipher}. */
    private static final String FIELD_PP_BABY_NAME = "babyName";

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final UserRepository users;
    private final AccountDekRepository accountDekRepo;
    private final KmsClient kmsClient;
    private final FieldEnvelopeDecryptor fieldEnvelopeDecryptor;
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
            AccountDekRepository accountDekRepo,
            KmsClient kmsClient,
            FieldEnvelopeDecryptor fieldEnvelopeDecryptor,
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
        this.accountDekRepo = accountDekRepo;
        this.kmsClient = kmsClient;
        this.fieldEnvelopeDecryptor = fieldEnvelopeDecryptor;
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
     * @return complete export response with all cipher fields decrypted to readable strings
     * @throws ApiException 404 {@code not_found} if the user is absent or soft-deleted
     */
    @Transactional(readOnly = true)
    public AccountExportResponse export(UUID userId) {
        // Guard: active user only — consistent with GET /account behavior.
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(404, "not_found"));
        if (user.getDeletedAt() != null) {
            throw new ApiException(404, "not_found");
        }

        // Log export event: user id + timestamp only; no health fields (PDPA / no-PII-in-logs).
        Instant exportedAt = Instant.now();
        log.info("PDPA export assembled for user={} at={}", userId, exportedAt);

        // ---- DEK loading (ADR Decision 5) ----
        // Load the wrapped DEK from account_dek and unwrap via KMS.
        // SECURITY: the plaintext DEK is transient in this stack frame ONLY.
        // It is NEVER logged, persisted, or emitted. GC-able after this method returns.
        // If no account_dek row exists (legacy/pre-provisioned account), dek = null.
        // The legacy dispatch path does NOT use the DEK, so pre-provision accounts
        // with legacy cipher rows export gracefully (no 500, no data loss).
        final String accountIdStr = userId.toString();
        final byte[] dek = accountDekRepo.findById(userId)
                .map(row -> kmsClient.decryptDek(row.getWrappedDek(), accountIdStr))
                .orElse(null);

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
                .map(p -> toProfileEntry(p, dek, accountIdStr))
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
        // note_cipher is now DECRYPTED and re-included as the readable "note" field
        // (ADR IMPORTANT-5: ม.30 completeness — the mother's session note is her health data;
        // the old "useless without DEK" exclusion rationale is gone now that export decrypts).
        List<KickCountSessionExportEntry> sessionEntries = kickCountSessions
                .findAllByUserIdForExport(userId)
                .stream()
                .map(s -> new KickCountSessionExportEntry(
                        s.getId(), s.getStartedAt(), s.getEndedAt(),
                        s.getDurationSeconds(), s.getMovementCount(),
                        s.getTargetCount(), s.getStatus(), s.getGestationalWeekAtStart(),
                        dispatchDecrypt(s.getNoteCipher(), dek,
                                new FieldAad(accountIdStr, COLL_KICK_COUNT_SESSION,
                                        s.getId().toString(), FIELD_KICK_NOTE)),
                        s.getCreatedAt(), s.getUpdatedAt(), s.getDeletedAt()))
                .toList();

        // ---- self-logs ----
        // All four cipher value columns are DEK-decrypted to readable strings (F3 fix).
        // Null cipher = tombstoned/crypto-shredded → null String in export (don't throw).
        List<SelfLogExportEntry> selfLogEntries = selfLogs
                .findAllByUserIdForExport(userId)
                .stream()
                .map(s -> {
                    String rowId = s.getId().toString();
                    return new SelfLogExportEntry(
                            s.getId(), s.getMetricType(),
                            dispatchDecrypt(s.getValueNumeric(), dek,
                                    new FieldAad(accountIdStr, COLL_SELF_LOG, rowId, FIELD_SELF_LOG_VALUE_NUMERIC)),
                            dispatchDecrypt(s.getValueNumericSecondary(), dek,
                                    new FieldAad(accountIdStr, COLL_SELF_LOG, rowId, FIELD_SELF_LOG_VALUE_NUMERIC_SECONDARY)),
                            dispatchDecrypt(s.getValueText(), dek,
                                    new FieldAad(accountIdStr, COLL_SELF_LOG, rowId, FIELD_SELF_LOG_VALUE_TEXT)),
                            dispatchDecrypt(s.getNoteCipher(), dek,
                                    new FieldAad(accountIdStr, COLL_SELF_LOG, rowId, FIELD_SELF_LOG_NOTE)),
                            s.getUnit(), s.getLoggedAt(),
                            s.getCreatedAt(), s.getUpdatedAt(), s.getDeletedAt());
                })
                .toList();

        // ---- medication plans ----
        // name_cipher and dose_cipher are DEK-decrypted to readable strings.
        // Null cipher on tombstone → null String.
        List<MedicationPlanExportEntry> medicationPlanEntries = medicationPlans
                .findAllByUserIdForExport(userId)
                .stream()
                .map(p -> {
                    String rowId = p.getId().toString();
                    return new MedicationPlanExportEntry(
                            p.getId(),
                            dispatchDecrypt(p.getNameCipher(), dek,
                                    new FieldAad(accountIdStr, COLL_MEDICATION_PLAN, rowId, FIELD_MED_PLAN_NAME)),
                            dispatchDecrypt(p.getDoseCipher(), dek,
                                    new FieldAad(accountIdStr, COLL_MEDICATION_PLAN, rowId, FIELD_MED_PLAN_DOSE)),
                            p.getScheduleRule(), p.isActive(), p.getSourceSuggestionStateId(),
                            p.getCreatedAt(), p.getUpdatedAt(), p.getDeletedAt());
                })
                .toList();

        // ---- medication logs ----
        // note_cipher is DEK-decrypted to a readable string.
        // Null cipher on tombstone → null String.
        List<MedicationLogExportEntry> medicationLogEntries = medicationLogs
                .findAllByUserIdForExport(userId)
                .stream()
                .map(l -> new MedicationLogExportEntry(
                        l.getId(), l.getMedicationPlanId(),
                        l.getOccurrenceTime(), l.getStatus(), l.getLoggedAt(),
                        dispatchDecrypt(l.getNoteCipher(), dek,
                                new FieldAad(accountIdStr, COLL_MEDICATION_LOG,
                                        l.getId().toString(), FIELD_MED_LOG_NOTE)),
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

    /**
     * Builds a {@link PregnancyProfileExportEntry} with DEK-aware decryption of name fields.
     *
     * <p>AAD RULING 2b: {@code pregnancy_profile} is a row-per-account table (one row per
     * account). The AAD {@code recordId} is the {@code accountId} string — the profile row UUID
     * is an implementation detail. The mobile client MUST use the same {@code recordId=accountId}
     * when encrypting. Frozen logical identifiers are in the {@code COLL_PREGNANCY_PROFILE} /
     * {@code FIELD_PP_*} constants — NEVER changed.
     *
     * @param p           the pregnancy profile entity
     * @param dek         256-bit plaintext DEK (may be null for legacy/pre-provisioned accounts)
     * @param accountIdStr the account UUID as string (= recordId for AAD)
     * @return export entry with decrypted name strings (null where cipher is null)
     */
    private PregnancyProfileExportEntry toProfileEntry(PregnancyProfile p,
                                                        byte[] dek, String accountIdStr) {
        return new PregnancyProfileExportEntry(
                p.getId(), p.getEdd(), p.getEddBasis(), p.getLifecycle(),
                p.getBirthDate(), p.getDeliveryType(), p.getBirthNote(),
                dispatchDecrypt(p.getMotherFirstNameCipher(), dek,
                        new FieldAad(accountIdStr, COLL_PREGNANCY_PROFILE,
                                accountIdStr, FIELD_PP_MOTHER_FIRST_NAME)),
                dispatchDecrypt(p.getMotherLastNameCipher(), dek,
                        new FieldAad(accountIdStr, COLL_PREGNANCY_PROFILE,
                                accountIdStr, FIELD_PP_MOTHER_LAST_NAME)),
                dispatchDecrypt(p.getBabyNameCipher(), dek,
                        new FieldAad(accountIdStr, COLL_PREGNANCY_PROFILE,
                                accountIdStr, FIELD_PP_BABY_NAME)),
                p.getCreatedAt(), p.getUpdatedAt(), p.getDeletedAt());
    }

    /**
     * Decision-4 version dispatch: decrypts a raw cipher byte array to a readable string.
     *
     * <p>Implements the IMPORTANT-3 server-side dispatch (mirror of the client read-path):
     * <ol>
     *   <li>If {@code rawCipherBytes} is {@code null} → return {@code null}
     *       (tombstoned / crypto-shredded field, §4.4(A)).</li>
     *   <li>Base64-encode the raw bytes to produce the "wire base64" string.</li>
     *   <li>Delegate to {@link FieldEnvelopeDecryptor#decryptFromBase64}, which applies:
     *       <ul>
     *         <li>first byte == {@code 0x01} AND length ≥ 29 → GCM decrypt (uses {@code dek})</li>
     *         <li>otherwise → legacy identity path: {@code UTF-8(base64decode(wire))} (no DEK needed)</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p><strong>SECURITY invariants (NEVER violate):</strong>
     * <ul>
     *   <li>The {@code dek} parameter is NEVER logged — even on error.</li>
     *   <li>Decrypted plaintext is NEVER logged — it is health data.</li>
     *   <li>{@code rawCipherBytes} is NEVER logged — it is cipher material.</li>
     * </ul>
     *
     * @param rawCipherBytes raw bytes from the {@code *_cipher} column (bytea entity field),
     *                       or {@code null} if the column is null
     * @param dek            256-bit plaintext DEK, or {@code null} for legacy-only accounts
     *                       (no {@code account_dek} row). Null DEK is safe for the legacy path
     *                       since the legacy branch does not call {@link FieldEnvelopeDecryptor}.
     * @param aad            field ownership context for GCM tag verification
     * @return decrypted plaintext, or {@code null} if {@code rawCipherBytes} is {@code null}
     * @throws SecurityException if GCM tag verification fails or the legacy path is rejected
     *                           after cutover ({@link com.momstarter.encryption.LegacyCutoverPolicy})
     */
    private String dispatchDecrypt(byte[] rawCipherBytes, byte[] dek, FieldAad aad) {
        if (rawCipherBytes == null) {
            return null;
        }
        // Base64-encode the raw bytea bytes to produce the wire format that
        // FieldEnvelopeDecryptor.decryptFromBase64 expects.
        String wireBase64 = Base64.getEncoder().encodeToString(rawCipherBytes);
        return fieldEnvelopeDecryptor.decryptFromBase64(wireBase64, dek, aad);
    }
}
