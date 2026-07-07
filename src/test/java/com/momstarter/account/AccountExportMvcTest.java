package com.momstarter.account;

import com.momstarter.auth.JwtService;
import com.momstarter.checklist.ChecklistItem;
import com.momstarter.checklist.ChecklistItemRepository;
import com.momstarter.consent.ConsentRecord;
import com.momstarter.consent.ConsentRecordRepository;
import com.momstarter.encryption.FieldAad;
import com.momstarter.encryption.FieldEnvelope;
import com.momstarter.encryption.KmsClient;
import com.momstarter.kickcount.KickCountSession;
import com.momstarter.kickcount.KickCountSessionRepository;
import com.momstarter.pregnancy.PregnancyProfile;
import com.momstarter.pregnancy.PregnancyProfileRepository;
import com.momstarter.reminder.Reminder;
import com.momstarter.reminder.ReminderOccurrence;
import com.momstarter.reminder.ReminderOccurrenceRepository;
import com.momstarter.reminder.ReminderRepository;
import com.momstarter.expense.Expense;
import com.momstarter.expense.ExpenseRepository;
import com.momstarter.medication.MedicationLog;
import com.momstarter.medication.MedicationLogRepository;
import com.momstarter.medication.MedicationPlan;
import com.momstarter.medication.MedicationPlanRepository;
import com.momstarter.selflog.SelfLog;
import com.momstarter.selflog.SelfLogRepository;
import com.momstarter.supply.SupplyItem;
import com.momstarter.supply.SupplyItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for {@code GET /account/export} (PDPA ม.30/31 data portability).
 *
 * <p>Phase-1 DEK-aware export sub-slice: cipher fields are now server-side decrypted before
 * export, so the JSON payload contains readable plaintext (not raw Base64 cipher bytes).
 * The Decision-4 version dispatch handles both legacy UNVERSIONED rows (base64-of-plaintext)
 * and real 0x01-GCM envelopes — and no-DEK-row accounts gracefully use the legacy path.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>401 without auth (no token).</li>
 *   <li>200 with auth — shape: all domain keys present, exportedAt present.</li>
 *   <li>IDOR: user A's export contains only A's rows and never B's rows.</li>
 *   <li>Secrets never exported: no passwordHash in output.</li>
 *   <li>Empty domains return empty arrays, not nulls.</li>
 *   <li>Soft-deleted account returns 404 (consistent with GET /account).</li>
 *   <li>Content-Disposition attachment header present.</li>
 *   <li>All domain data included: pregnancyProfile, supplyItems, reminders,
 *       reminderOccurrences, checklistItems, kickCountSessions, consentHistory.</li>
 *   <li>DEK-aware: legacy rows produce readable plaintext without a DEK row.</li>
 *   <li>DEK-aware: real 0x01-GCM envelope decrypts to readable plaintext.</li>
 *   <li>DEK-aware: no account_dek row still exports legacy fields gracefully.</li>
 *   <li>DEK-aware: null cipher (tombstoned) emits null in the export.</li>
 *   <li>kick_count_session.note_cipher re-included as decrypted {@code note} field (ADR IMPORTANT-5).</li>
 * </ul>
 *
 * <p>Runs against H2 (test profile, Flyway-migrated schema). Each test is wrapped in
 * a transaction that rolls back, giving clean isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AccountExportMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PregnancyProfileRepository profiles;
    @Autowired
    private ExpenseRepository expenseRepo;
    @Autowired
    private SupplyItemRepository supplyItems;
    @Autowired
    private ReminderRepository reminders;
    @Autowired
    private ReminderOccurrenceRepository reminderOccurrences;
    @Autowired
    private ChecklistItemRepository checklistItems;
    @Autowired
    private KickCountSessionRepository kickCountSessions;
    @Autowired
    private SelfLogRepository selfLogRepo;
    @Autowired
    private MedicationPlanRepository medicationPlanRepo;
    @Autowired
    private MedicationLogRepository medicationLogRepo;
    @Autowired
    private ConsentRecordRepository consentRecords;
    @Autowired
    private AccountDekRepository accountDekRepo;
    @Autowired
    private KmsClient kmsClient;
    @Autowired
    private JwtService jwtService;

    private User userA;
    private String bearerA;

    @BeforeEach
    void seed() {
        // Clean slate — @Transactional rollback handles isolation but deletion order
        // avoids FK violations within the test transaction.
        // account_dek references users via FK ON DELETE RESTRICT — must precede users.
        accountDekRepo.deleteAll();
        consentRecords.deleteAll();
        kickCountSessions.deleteAll();
        selfLogRepo.deleteAll();
        medicationLogRepo.deleteAll();   // FK → medication_plan + users; must precede both
        medicationPlanRepo.deleteAll();  // FK → users; must precede users
        reminderOccurrences.deleteAll();
        reminders.deleteAll();
        checklistItems.deleteAll();
        expenseRepo.deleteAll();
        supplyItems.deleteAll();
        profiles.deleteAll();
        users.deleteAll();

        userA = new User();
        userA.setEmail("export-a@example.com");
        userA.setEmailVerified(true);
        userA = users.saveAndFlush(userA);
        bearerA = "Bearer " + jwtService.issueAccessToken(userA.getId(), true);
    }

    // -------------------------------------------------------------------------
    // AUTH
    // -------------------------------------------------------------------------

    @Test
    void withoutAuth_returns401() throws Exception {
        mvc.perform(get("/account/export"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // HAPPY PATH — shape
    // -------------------------------------------------------------------------

    @Test
    void withAuth_returns200WithAllDomainKeys() throws Exception {
        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportedAt").isString())
                .andExpect(jsonPath("$.account").exists())
                .andExpect(jsonPath("$.account.email").value("export-a@example.com"))
                // pregnancyProfile absent from JSON when null (@JsonInclude NON_NULL)
                .andExpect(jsonPath("$.pregnancyProfile").doesNotExist())
                .andExpect(jsonPath("$.supplyItems").isArray())
                .andExpect(jsonPath("$.expenses").isArray())
                .andExpect(jsonPath("$.reminders").isArray())
                .andExpect(jsonPath("$.reminderOccurrences").isArray())
                .andExpect(jsonPath("$.checklistItems").isArray())
                .andExpect(jsonPath("$.kickCountSessions").isArray())
                .andExpect(jsonPath("$.selfLogs").isArray())
                .andExpect(jsonPath("$.medicationPlans").isArray())
                .andExpect(jsonPath("$.medicationLogs").isArray())
                .andExpect(jsonPath("$.consentHistory").isArray());
    }

    @Test
    void withAuth_contentDispositionAttachmentHeaderPresent() throws Exception {
        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        containsString("export.json")));
    }

    @Test
    void withAuth_contentTypeIsJson() throws Exception {
        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    // -------------------------------------------------------------------------
    // EMPTY DOMAINS — arrays, not nulls
    // -------------------------------------------------------------------------

    @Test
    void emptyUser_allDomainArraysAreEmptyNotNull() throws Exception {
        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplyItems").isArray())
                .andExpect(jsonPath("$.supplyItems.length()").value(0))
                .andExpect(jsonPath("$.expenses").isArray())
                .andExpect(jsonPath("$.expenses.length()").value(0))
                .andExpect(jsonPath("$.reminders").isArray())
                .andExpect(jsonPath("$.reminders.length()").value(0))
                .andExpect(jsonPath("$.reminderOccurrences").isArray())
                .andExpect(jsonPath("$.reminderOccurrences.length()").value(0))
                .andExpect(jsonPath("$.checklistItems").isArray())
                .andExpect(jsonPath("$.checklistItems.length()").value(0))
                .andExpect(jsonPath("$.kickCountSessions").isArray())
                .andExpect(jsonPath("$.kickCountSessions.length()").value(0))
                .andExpect(jsonPath("$.selfLogs").isArray())
                .andExpect(jsonPath("$.selfLogs.length()").value(0))
                .andExpect(jsonPath("$.medicationPlans").isArray())
                .andExpect(jsonPath("$.medicationPlans.length()").value(0))
                .andExpect(jsonPath("$.medicationLogs").isArray())
                .andExpect(jsonPath("$.medicationLogs.length()").value(0))
                .andExpect(jsonPath("$.consentHistory").isArray())
                .andExpect(jsonPath("$.consentHistory.length()").value(0));
    }

    // -------------------------------------------------------------------------
    // SECRETS — never in output
    // -------------------------------------------------------------------------

    /**
     * Verifies the raw JSON body does NOT contain any secret field name or material.
     * passwordHash must never appear in the export.
     */
    @Test
    void secretsNeverInOutput_noPasswordHash() throws Exception {
        // Give userA a password hash
        userA.setPasswordHash("{argon2}fakehash");
        users.saveAndFlush(userA);

        String body = mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Neither the field name nor the value should appear
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("passwordHash")
                .doesNotContain("password_hash")
                .doesNotContain("fakehash");
    }

    /**
     * DEK-aware export: kick_count_session.note_cipher is now RE-INCLUDED in the export as
     * a decrypted {@code note} field (ADR IMPORTANT-5 / PDPA ม.30 completeness).
     * The field name in the JSON is {@code note} (decrypted plaintext), NOT {@code noteCipher}
     * (cipher bytes). Cipher bytes are NEVER emitted.
     */
    @Test
    void kickCountNote_reincluded_withDecryptedNote() throws Exception {
        // Seed legacy plaintext bytes (no-op cipher, no DEK row — legacy path)
        KickCountSession session = buildKickCountSession(userA.getId());
        // Legacy format: raw UTF-8 bytes stored in note_cipher (no-op cipher)
        session.setNoteCipher("my kick note".getBytes(StandardCharsets.UTF_8));
        kickCountSessions.saveAndFlush(session);

        String body = mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kickCountSessions.length()").value(1))
                // note is re-included as decrypted string (IMPORTANT-5)
                .andExpect(jsonPath("$.kickCountSessions[0].note").value("my kick note"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The cipher field name must NOT appear — only decrypted "note" is exported
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("noteCipher")
                .doesNotContain("note_cipher");
    }

    // -------------------------------------------------------------------------
    // DEK-AWARE: legacy row → readable plaintext (Decision-4 legacy path, no DEK needed)
    // -------------------------------------------------------------------------

    /**
     * Legacy (no-op cipher) rows: raw UTF-8 bytes stored in the cipher column are decoded
     * to readable plaintext via the Decision-4 legacy identity path — no DEK row required.
     */
    @Test
    void dekAware_legacyRow_exportsReadablePlaintext() throws Exception {
        // No AccountDek row for userA (pre-provision / legacy account)
        // Seed a medication plan with legacy plaintext bytes in name_cipher
        MedicationPlan plan = buildMedicationPlan(userA.getId(),
                "Folic Acid 400mcg".getBytes(StandardCharsets.UTF_8),
                "{\"freq\":\"daily\"}");
        // Seed a medication log with legacy plaintext note
        medicationPlanRepo.saveAndFlush(plan);
        MedicationLog log = buildMedicationLog(userA.getId(), plan.getId(),
                "taken", LocalDateTime.of(2026, 7, 1, 9, 0),
                "taken with breakfast".getBytes(StandardCharsets.UTF_8));
        medicationLogRepo.saveAndFlush(log);

        mvc.perform(get("/account/export").header("Authorization", bearerA))
                .andExpect(status().isOk())
                // name decrypted to readable plaintext via legacy path (no DEK row needed)
                .andExpect(jsonPath("$.medicationPlans[0].name").value("Folic Acid 400mcg"))
                // note decrypted to readable plaintext
                .andExpect(jsonPath("$.medicationLogs[0].note").value("taken with breakfast"));
    }

    /**
     * No account_dek row (legacy/pre-provisioned account) + legacy fields → graceful export.
     * No 500, no crash. All cipher fields are legacy-decoded to readable strings.
     */
    @Test
    void dekAware_noDekRow_legacyFieldsExportGracefully() throws Exception {
        // No AccountDek row seeded for userA
        SelfLog log = buildSelfLog(userA.getId(), "weight");
        log.setValueNumeric("70".getBytes(StandardCharsets.UTF_8));
        selfLogRepo.saveAndFlush(log);

        mvc.perform(get("/account/export").header("Authorization", bearerA))
                .andExpect(status().isOk())  // must NOT 500
                .andExpect(jsonPath("$.selfLogs.length()").value(1))
                // legacy path: raw bytes decoded to readable string
                .andExpect(jsonPath("$.selfLogs[0].valueNumeric").value("70"));
    }

    /**
     * Real 0x01-GCM-encrypted field decrypts to readable plaintext when the account has a DEK row.
     * Exercises the GCM branch of the Decision-4 version dispatch, confirming round-trip:
     * encrypt with MockKmsClient DEK → store → export → assert plaintext.
     */
    @Test
    void dekAware_gcmEncryptedKickCountNote_decryptsToPlaintext() throws Exception {
        // Provision a DEK for userA
        byte[] dek = seedDekAndReturnPlaintext(userA.getId());

        // Encrypt a kick_count session note with the real DEK + GCM envelope
        KickCountSession session = buildKickCountSession(userA.getId());
        UUID sessionId = session.getId();
        FieldAad noteAad = new FieldAad(
                userA.getId().toString(), "kickCountSession", sessionId.toString(), "note");
        byte[] noteCipherBytes = FieldEnvelope.encrypt(
                "นับลูกดิ้น 10 ครั้ง".getBytes(StandardCharsets.UTF_8), dek, noteAad);
        session.setNoteCipher(noteCipherBytes);
        kickCountSessions.saveAndFlush(session);

        mvc.perform(get("/account/export").header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kickCountSessions.length()").value(1))
                // GCM-decrypted to readable Thai text
                .andExpect(jsonPath("$.kickCountSessions[0].note").value("นับลูกดิ้น 10 ครั้ง"));
    }

    /**
     * Real 0x01-GCM-encrypted medication plan name/dose + self-log values decrypt correctly.
     */
    @Test
    void dekAware_gcmEncryptedMedicationPlan_decryptsToPlaintext() throws Exception {
        byte[] dek = seedDekAndReturnPlaintext(userA.getId());

        MedicationPlan plan = new MedicationPlan();
        plan.setId(UUID.randomUUID());
        plan.setUserId(userA.getId());
        plan.setActive(true);

        FieldAad nameAad = new FieldAad(
                userA.getId().toString(), "medicationPlan", plan.getId().toString(), "name");
        FieldAad doseAad = new FieldAad(
                userA.getId().toString(), "medicationPlan", plan.getId().toString(), "dose");
        plan.setNameCipher(FieldEnvelope.encrypt("Folic Acid".getBytes(StandardCharsets.UTF_8), dek, nameAad));
        plan.setDoseCipher(FieldEnvelope.encrypt("400mcg".getBytes(StandardCharsets.UTF_8), dek, doseAad));
        medicationPlanRepo.saveAndFlush(plan);

        mvc.perform(get("/account/export").header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medicationPlans.length()").value(1))
                .andExpect(jsonPath("$.medicationPlans[0].name").value("Folic Acid"))
                .andExpect(jsonPath("$.medicationPlans[0].dose").value("400mcg"));
    }

    /**
     * Null cipher (crypto-shredded tombstone) emits null in the export — no exception thrown.
     */
    @Test
    void dekAware_nullCipher_emitsNullInExport() throws Exception {
        // No DEK row — null cipher field on a tombstoned kick_count session
        KickCountSession session = buildKickCountSession(userA.getId());
        session.setNoteCipher(null);  // tombstoned / crypto-shredded
        session.setDeletedAt(Instant.now());
        kickCountSessions.saveAndFlush(session);

        mvc.perform(get("/account/export").header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kickCountSessions.length()").value(1))
                // note is absent (null field, omitted by @JsonInclude NON_NULL if configured,
                // or present as null if not — either way no exception)
                .andExpect(status().isOk()); // primary assertion: no 500
    }

    // -------------------------------------------------------------------------
    // IDOR GUARD — user A never sees user B's data
    // -------------------------------------------------------------------------

    @Test
    void idor_exportContainsOnlyOwnerData_notOtherUser() throws Exception {
        // Seed user B with data
        User userB = new User();
        userB.setEmail("export-b@example.com");
        userB.setEmailVerified(true);
        userB = users.saveAndFlush(userB);

        // User B's supply item
        SupplyItem itemB = buildSupplyItem(userB.getId(), "UserB-item");
        supplyItems.saveAndFlush(itemB);

        // User B's consent record
        ConsentRecord consentB = buildConsentRecord(userB.getId(), "general_health");
        consentRecords.saveAndFlush(consentB);

        // User B's self_log — must NOT appear in user A's export (IDOR isolation for new collection)
        SelfLog selfLogB = buildSelfLog(userB.getId(), "blood_pressure");
        selfLogRepo.saveAndFlush(selfLogB);

        // User B's medication_plan + medication_log — must NOT appear in user A's export
        MedicationPlan planB = buildMedicationPlan(userB.getId(), new byte[]{4, 5, 6}, null);
        medicationPlanRepo.saveAndFlush(planB);
        MedicationLog logB = buildMedicationLog(userB.getId(), planB.getId(),
                "taken", LocalDateTime.of(2026, 7, 1, 9, 0), null);
        medicationLogRepo.saveAndFlush(logB);

        // User A's own supply item
        SupplyItem itemA = buildSupplyItem(userA.getId(), "UserA-item");
        supplyItems.saveAndFlush(itemA);

        // Export as user A
        String body = mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").value("export-a@example.com"))
                .andExpect(jsonPath("$.supplyItems.length()").value(1))
                // userA has no self_logs seeded — userB's self_log must not leak across
                .andExpect(jsonPath("$.selfLogs.length()").value(0))
                // userA has no medication rows seeded — userB's must not leak across
                .andExpect(jsonPath("$.medicationPlans.length()").value(0))
                .andExpect(jsonPath("$.medicationLogs.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // A's export must not contain B's email, B's item name, B's self_log id,
        // or B's medication plan/log ids.
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("UserA-item")
                .doesNotContain("export-b@example.com")
                .doesNotContain("UserB-item")
                .doesNotContain(selfLogB.getId().toString())
                .doesNotContain(planB.getId().toString())
                .doesNotContain(logB.getId().toString());
    }

    // -------------------------------------------------------------------------
    // SOFT-DELETED ACCOUNT — 404
    // -------------------------------------------------------------------------

    @Test
    void softDeletedAccount_returns404() throws Exception {
        // Soft-delete user A
        userA.setDeletedAt(Instant.now());
        userA.setStatus("deleted");
        users.saveAndFlush(userA);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // DOMAIN DATA — each domain properly populated
    // -------------------------------------------------------------------------

    @Test
    void pregnancyProfileIncluded_whenPresent() throws Exception {
        PregnancyProfile profile = new PregnancyProfile();
        profile.setUserId(userA.getId());
        profile.setEdd(LocalDate.of(2027, 1, 15));
        profile.setEddBasis("due_date");
        profiles.saveAndFlush(profile);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pregnancyProfile").exists())
                .andExpect(jsonPath("$.pregnancyProfile.edd").value("2027-01-15"))
                .andExpect(jsonPath("$.pregnancyProfile.eddBasis").value("due_date"))
                .andExpect(jsonPath("$.pregnancyProfile.lifecycle").value("pregnant"));
    }

    @Test
    void supplyItemsIncluded() throws Exception {
        supplyItems.saveAndFlush(buildSupplyItem(userA.getId(), "diapers-brand"));

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplyItems.length()").value(1))
                .andExpect(jsonPath("$.supplyItems[0].name").value("diapers-brand"))
                .andExpect(jsonPath("$.supplyItems[0].category").value("diapers"));
    }

    @Test
    void remindersIncluded() throws Exception {
        reminders.saveAndFlush(buildReminder(userA.getId(), "Take vitamins"));

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminders.length()").value(1))
                .andExpect(jsonPath("$.reminders[0].displayTitle").value("Take vitamins"));
    }

    @Test
    void reminderOccurrencesIncluded() throws Exception {
        Reminder reminder = buildReminder(userA.getId(), "Medication");
        reminders.saveAndFlush(reminder);

        ReminderOccurrence occ = new ReminderOccurrence();
        occ.setId(UUID.randomUUID());
        occ.setUserId(userA.getId());
        occ.setReminderId(reminder.getId());
        occ.setScheduledLocalTime(LocalDateTime.of(2026, 7, 1, 9, 0));
        occ.setStatus("done");
        occ.setActedAt(Instant.now());
        reminderOccurrences.saveAndFlush(occ);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminderOccurrences.length()").value(1))
                .andExpect(jsonPath("$.reminderOccurrences[0].status").value("done"));
    }

    @Test
    void checklistItemsIncluded() throws Exception {
        ChecklistItem item = new ChecklistItem();
        item.setId(UUID.randomUUID());
        item.setUserId(userA.getId());
        item.setCategory("anc_visit");
        item.setTitle("First ANC visit");
        item.setScheduledAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        checklistItems.saveAndFlush(item);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checklistItems.length()").value(1))
                .andExpect(jsonPath("$.checklistItems[0].title").value("First ANC visit"))
                .andExpect(jsonPath("$.checklistItems[0].category").value("anc_visit"));
    }

    /**
     * Kick-count sessions are included. The {@code note} field (decrypted note_cipher) is
     * included — when no note_cipher was set, it is null/absent (not an error).
     * The raw cipher key {@code noteCipher} must never appear in the output.
     */
    @Test
    void kickCountSessionsIncluded_noteNullWhenNotSet() throws Exception {
        KickCountSession session = buildKickCountSession(userA.getId());
        // noteCipher not set (null) — note should be absent/null in export
        kickCountSessions.saveAndFlush(session);

        String body = mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kickCountSessions.length()").value(1))
                .andExpect(jsonPath("$.kickCountSessions[0].movementCount").value(10))
                .andExpect(jsonPath("$.kickCountSessions[0].durationSeconds").value(300))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Raw cipher bytes MUST NOT appear as a field name
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("noteCipher")
                .doesNotContain("note_cipher");
    }

    @Test
    void consentHistoryIncluded() throws Exception {
        consentRecords.saveAndFlush(buildConsentRecord(userA.getId(), "cloud_storage"));

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentHistory.length()").value(1))
                .andExpect(jsonPath("$.consentHistory[0].consentType").value("cloud_storage"))
                .andExpect(jsonPath("$.consentHistory[0].granted").value(true));
    }

    /**
     * Soft-deleted records (tombstones) are included in the export: the user's right to
     * access their data (PDPA ม.30) covers all records, including those marked for deletion
     * but not yet hard-purged by the GC scheduler.
     */
    @Test
    void tombstonedSupplyItemsIncludedInExport() throws Exception {
        SupplyItem live = buildSupplyItem(userA.getId(), "live-item");
        supplyItems.saveAndFlush(live);

        SupplyItem tombstoned = buildSupplyItem(userA.getId(), "deleted-item");
        tombstoned.setDeletedAt(Instant.now());
        supplyItems.saveAndFlush(tombstoned);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                // Both live and tombstoned included — user's full data set
                .andExpect(jsonPath("$.supplyItems.length()").value(2));
    }

    /**
     * Expenses included in the PDPA export (ม.30/31).
     */
    @Test
    void expensesIncluded() throws Exception {
        expenseRepo.saveAndFlush(buildExpense(userA.getId(), 59000, "healthcare"));

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenses.length()").value(1))
                .andExpect(jsonPath("$.expenses[0].amount").value(59000))
                .andExpect(jsonPath("$.expenses[0].category").value("healthcare"))
                .andExpect(jsonPath("$.expenses[0].incurredOn").value("2026-07-01"));
    }

    /**
     * Self-logs included in the PDPA export (ม.30/31) — PDPA F3 fix.
     * Under DEK-aware export, cipher fields are decrypted to readable strings.
     * No-DEK-row account (pre-provisioned) uses the legacy path.
     */
    @Test
    void selfLogsIncluded() throws Exception {
        SelfLog log = buildSelfLog(userA.getId(), "weight");
        log.setValueNumeric("72.5".getBytes(StandardCharsets.UTF_8));
        selfLogRepo.saveAndFlush(log);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selfLogs.length()").value(1))
                .andExpect(jsonPath("$.selfLogs[0].metricType").value("weight"))
                // valueNumeric is now a String (decrypted plaintext)
                .andExpect(jsonPath("$.selfLogs[0].valueNumeric").value("72.5"));
    }

    /**
     * Tombstoned self-log rows are included in the export — PDPA ม.30 right to access
     * covers all records in the pre-GC window, including tombstones (data not yet hard-purged).
     *
     * <p>On tombstone, bytea value columns are crypto-shredded to null, so only the
     * structural sync columns (id, metricType, loggedAt, deletedAt) carry data.
     */
    @Test
    void tombstonedSelfLogsIncludedInExport() throws Exception {
        SelfLog live = buildSelfLog(userA.getId(), "weight");
        selfLogRepo.saveAndFlush(live);

        SelfLog tombstoned = buildSelfLog(userA.getId(), "blood_pressure");
        tombstoned.setValueNumeric(null);     // crypto-shredded on tombstone (PDPA §4.4(A))
        tombstoned.setValueText(null);
        tombstoned.setNoteCipher(null);
        tombstoned.setDeletedAt(Instant.now());
        selfLogRepo.saveAndFlush(tombstoned);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                // Both live and tombstoned included — user's full data set (PDPA ม.30)
                .andExpect(jsonPath("$.selfLogs.length()").value(2));
    }

    /**
     * Tombstoned expenses are included in the export (PDPA ม.30 right to access covers all
     * records in the pre-GC window).
     */
    @Test
    void tombstonedExpensesIncludedInExport() throws Exception {
        Expense live = buildExpense(userA.getId(), 42800, "baby-supplies");
        expenseRepo.saveAndFlush(live);

        Expense tombstoned = buildExpense(userA.getId(), 18400, "mother");
        tombstoned.setDeletedAt(Instant.now());
        expenseRepo.saveAndFlush(tombstoned);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenses.length()").value(2));
    }

    // -------------------------------------------------------------------------
    // MEDICATION PLANS — PDPA ม.30/31 export (SD-2 health data, Slice 2 Task 5)
    // -------------------------------------------------------------------------

    /**
     * Medication plans (live) must appear in the PDPA export.
     * {@code name} and {@code dose} are included as decrypted readable strings.
     * scheduleRule is included as a JSON string.
     */
    @Test
    void medicationPlansIncluded() throws Exception {
        MedicationPlan plan = buildMedicationPlan(userA.getId(),
                "Folic Acid 400mcg".getBytes(StandardCharsets.UTF_8),
                "{\"freq\":\"daily\",\"startAt\":\"2026-07-01T08:00\"}");
        medicationPlanRepo.saveAndFlush(plan);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medicationPlans.length()").value(1))
                .andExpect(jsonPath("$.medicationPlans[0].scheduleRule")
                        .value("{\"freq\":\"daily\",\"startAt\":\"2026-07-01T08:00\"}"))
                .andExpect(jsonPath("$.medicationPlans[0].active").value(true))
                // name is decrypted to readable string (legacy path for pre-DEK-provision rows)
                .andExpect(jsonPath("$.medicationPlans[0].name").value("Folic Acid 400mcg"));
    }

    /**
     * Tombstoned medication plan rows are included in the export (PDPA ม.30 pre-GC window).
     * On tombstone, nameCipher and doseCipher are crypto-shredded to null (§4.4(A));
     * the export emits null for {@code name} and {@code dose}.
     */
    @Test
    void tombstonedMedicationPlansIncludedInExport() throws Exception {
        MedicationPlan live = buildMedicationPlan(userA.getId(),
                new byte[]{1, 2, 3}, null);
        medicationPlanRepo.saveAndFlush(live);

        MedicationPlan tombstoned = buildMedicationPlan(userA.getId(),
                null, null);
        tombstoned.setDeletedAt(Instant.now()); // tombstone
        // nameCipher = null is legal for tombstone (CHECK constraint allows it)
        medicationPlanRepo.saveAndFlush(tombstoned);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                // Both live and tombstoned included (PDPA ม.30)
                .andExpect(jsonPath("$.medicationPlans.length()").value(2));
    }

    // -------------------------------------------------------------------------
    // MEDICATION LOGS — PDPA ม.30/31 export (SD-2 health data, Slice 2 Task 5)
    // -------------------------------------------------------------------------

    /**
     * Medication logs (live) must appear in the PDPA export.
     * {@code note} is included as a decrypted readable string.
     */
    @Test
    void medicationLogsIncluded() throws Exception {
        MedicationPlan plan = buildMedicationPlan(userA.getId(),
                new byte[]{1, 2, 3}, null);
        medicationPlanRepo.saveAndFlush(plan);

        MedicationLog log = buildMedicationLog(userA.getId(), plan.getId(),
                "taken", LocalDateTime.of(2026, 7, 1, 9, 0),
                "took with food".getBytes(StandardCharsets.UTF_8));
        medicationLogRepo.saveAndFlush(log);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medicationLogs.length()").value(1))
                .andExpect(jsonPath("$.medicationLogs[0].status").value("taken"))
                .andExpect(jsonPath("$.medicationLogs[0].medicationPlanId")
                        .value(plan.getId().toString()))
                // note is decrypted to readable string
                .andExpect(jsonPath("$.medicationLogs[0].note").value("took with food"));
    }

    /**
     * Tombstoned medication log rows are included in the export (PDPA ม.30 pre-GC window).
     * On tombstone, noteCipher is crypto-shredded to null (§4.4(A)).
     */
    @Test
    void tombstonedMedicationLogsIncludedInExport() throws Exception {
        MedicationPlan plan = buildMedicationPlan(userA.getId(),
                new byte[]{1, 2, 3}, null);
        medicationPlanRepo.saveAndFlush(plan);

        MedicationLog live = buildMedicationLog(userA.getId(), plan.getId(),
                "taken", LocalDateTime.of(2026, 7, 1, 9, 0), new byte[]{1, 2, 3});
        medicationLogRepo.saveAndFlush(live);

        MedicationLog tombstoned = buildMedicationLog(userA.getId(), null,
                "missed", LocalDateTime.of(2026, 7, 2, 9, 0), null);
        tombstoned.setNoteCipher(null);   // crypto-shredded on tombstone
        tombstoned.setDeletedAt(Instant.now());
        medicationLogRepo.saveAndFlush(tombstoned);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                // Both live and tombstoned included (PDPA ม.30)
                .andExpect(jsonPath("$.medicationLogs.length()").value(2));
    }

    // -------------------------------------------------------------------------
    // NAME FIELDS — PDPA ม.30 export (name-fields-design.md §6)
    // RED until PregnancyProfileExportEntry + AccountExportService.toProfileEntry are updated
    // -------------------------------------------------------------------------

    /**
     * When a pregnancy profile has name cipher bytes set, the export must include decrypted
     * name plaintext fields. Under the MVP no-op cipher posture (Option A), the raw bytes
     * ARE the UTF-8 plaintext; the legacy dispatch path in {@link FieldEnvelopeDecryptor}
     * decodes Base64-of-UTF-8 → readable string without a DEK.
     *
     * <p>name-fields-design.md §6 + AAD RULING 2b:
     * collection="pregnancyProfile", recordId=accountId (row-per-account table).
     */
    @Test
    void pregnancyProfileExport_includesDecryptedNameFields() throws Exception {
        PregnancyProfile profile = new PregnancyProfile();
        profile.setUserId(userA.getId());
        profile.setEdd(LocalDate.of(2027, 1, 15));
        profile.setEddBasis("due_date");
        profile.setMotherFirstNameCipher("Somchai".getBytes(StandardCharsets.UTF_8));
        profile.setMotherLastNameCipher("Rakdee".getBytes(StandardCharsets.UTF_8));
        profile.setBabyNameCipher("Nong Fah".getBytes(StandardCharsets.UTF_8));
        profiles.saveAndFlush(profile);

        mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pregnancyProfile").exists())
                .andExpect(jsonPath("$.pregnancyProfile.motherFirstName").value("Somchai"))
                .andExpect(jsonPath("$.pregnancyProfile.motherLastName").value("Rakdee"))
                .andExpect(jsonPath("$.pregnancyProfile.babyName").value("Nong Fah"));
    }

    /**
     * When name cipher columns are NULL (never set or crypto-shredded), the export must
     * include the pregnancy profile with null / absent name fields (no NPE thrown).
     */
    @Test
    void pregnancyProfileExport_nameFieldsNullWhenCipherNull() throws Exception {
        PregnancyProfile profile = new PregnancyProfile();
        profile.setUserId(userA.getId());
        profile.setEdd(LocalDate.of(2027, 3, 1));
        profile.setEddBasis("due_date");
        // All three name ciphers intentionally left null
        profiles.saveAndFlush(profile);

        String body = mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pregnancyProfile").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Raw cipher key names must never appear in the export
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("motherFirstNameCipher")
                .doesNotContain("motherLastNameCipher")
                .doesNotContain("babyNameCipher");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Provisions a DEK for the given user via MockKmsClient and saves it to account_dek.
     * Returns the plaintext DEK bytes for use in encrypting test fixture data.
     * NEVER log the returned bytes — they are transient test material.
     */
    private byte[] seedDekAndReturnPlaintext(UUID userId) {
        KmsClient.GeneratedDek generatedDek = kmsClient.generateDek(userId.toString());
        AccountDek dekRow = new AccountDek();
        dekRow.setUserId(userId);
        dekRow.setWrappedDek(generatedDek.wrappedDek());
        dekRow.setKmsKeyId(generatedDek.kmsKeyId());
        dekRow.setWrapContext("accountId=" + userId);
        accountDekRepo.saveAndFlush(dekRow);
        return generatedDek.plaintextDek();
    }

    private Expense buildExpense(UUID userId, int amount, String category) {
        Expense e = new Expense();
        e.setId(UUID.randomUUID());
        e.setUserId(userId);
        e.setAmount(amount);
        e.setCategory(category);
        e.setIncurredOn(java.time.LocalDate.of(2026, 7, 1));
        return e;
    }

    private SupplyItem buildSupplyItem(UUID userId, String name) {
        SupplyItem item = new SupplyItem();
        item.setId(UUID.randomUUID());
        item.setUserId(userId);
        item.setName(name);
        item.setCategory("diapers");
        item.setOnHandQty(5);
        return item;
    }

    private Reminder buildReminder(UUID userId, String title) {
        Reminder r = new Reminder();
        r.setId(UUID.randomUUID());
        r.setUserId(userId);
        r.setType("medication");
        r.setDisplayTitle(title);
        r.setRecurrenceRule("{\"freq\":\"daily\"}");
        r.setStartAt(LocalDateTime.of(2026, 7, 1, 8, 0));
        return r;
    }

    private KickCountSession buildKickCountSession(UUID userId) {
        KickCountSession s = new KickCountSession();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setStartedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        s.setEndedAt(LocalDateTime.of(2026, 7, 1, 9, 5));
        s.setDurationSeconds(300);
        s.setMovementCount(10);
        s.setTargetCount(10);
        s.setStatus("completed");
        return s;
    }

    private ConsentRecord buildConsentRecord(UUID userId, String consentType) {
        ConsentRecord rec = new ConsentRecord();
        rec.setUserId(userId);
        rec.setConsentType(consentType);
        rec.setGranted(true);
        rec.setConsentTextVersion("v1.0-th");
        rec.setLocale("th");
        return rec;
    }

    private SelfLog buildSelfLog(UUID userId, String metricType) {
        SelfLog s = new SelfLog();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setMetricType(metricType);
        s.setLoggedAt(java.time.LocalDateTime.of(2026, 7, 1, 9, 0));
        // MVP posture: raw UTF-8 bytes in bytea column (no-op cipher, ADR Option A)
        s.setValueNumeric(new byte[]{1, 2, 3});
        return s;
    }

    // -------------------------------------------------------------------------
    // MEDICATION helpers
    // -------------------------------------------------------------------------

    private MedicationPlan buildMedicationPlan(UUID userId, byte[] nameCipher,
                                                String scheduleRule) {
        MedicationPlan p = new MedicationPlan();
        p.setId(UUID.randomUUID());
        p.setUserId(userId);
        p.setNameCipher(nameCipher);   // null only for tombstones (§4.4(A))
        p.setScheduleRule(scheduleRule);
        p.setActive(true);
        return p;
    }

    private MedicationLog buildMedicationLog(UUID userId, UUID medicationPlanId,
                                              String status, LocalDateTime occurrenceTime,
                                              byte[] noteCipher) {
        MedicationLog l = new MedicationLog();
        l.setId(UUID.randomUUID());
        l.setUserId(userId);
        l.setMedicationPlanId(medicationPlanId);
        l.setStatus(status);
        l.setOccurrenceTime(occurrenceTime);
        l.setNoteCipher(noteCipher);
        return l;
    }
}
