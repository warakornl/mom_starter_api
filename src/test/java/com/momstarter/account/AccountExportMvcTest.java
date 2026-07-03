package com.momstarter.account;

import com.momstarter.auth.JwtService;
import com.momstarter.checklist.ChecklistItem;
import com.momstarter.checklist.ChecklistItemRepository;
import com.momstarter.consent.ConsentRecord;
import com.momstarter.consent.ConsentRecordRepository;
import com.momstarter.kickcount.KickCountSession;
import com.momstarter.kickcount.KickCountSessionRepository;
import com.momstarter.pregnancy.PregnancyProfile;
import com.momstarter.pregnancy.PregnancyProfileRepository;
import com.momstarter.reminder.Reminder;
import com.momstarter.reminder.ReminderOccurrence;
import com.momstarter.reminder.ReminderOccurrenceRepository;
import com.momstarter.reminder.ReminderRepository;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for {@code GET /account/export} (PDPA ม.30/31 data portability).
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>401 without auth (no token).</li>
 *   <li>200 with auth — shape: all domain keys present, exportedAt present.</li>
 *   <li>IDOR: user A's export contains only A's rows and never B's rows.</li>
 *   <li>Secrets never exported: no passwordHash, no noteCipher in output.</li>
 *   <li>Empty domains return empty arrays, not nulls.</li>
 *   <li>Soft-deleted account returns 404 (consistent with GET /account).</li>
 *   <li>Content-Disposition attachment header present.</li>
 *   <li>All domain data included: pregnancyProfile, supplyItems, reminders,
 *       reminderOccurrences, checklistItems, kickCountSessions, consentHistory.</li>
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
    private ConsentRecordRepository consentRecords;
    @Autowired
    private JwtService jwtService;

    private User userA;
    private String bearerA;

    @BeforeEach
    void seed() {
        // Clean slate — @Transactional rollback handles isolation but deletion order
        // avoids FK violations within the test transaction.
        consentRecords.deleteAll();
        kickCountSessions.deleteAll();
        reminderOccurrences.deleteAll();
        reminders.deleteAll();
        checklistItems.deleteAll();
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
                .andExpect(jsonPath("$.reminders").isArray())
                .andExpect(jsonPath("$.reminderOccurrences").isArray())
                .andExpect(jsonPath("$.checklistItems").isArray())
                .andExpect(jsonPath("$.kickCountSessions").isArray())
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
                .andExpect(jsonPath("$.reminders").isArray())
                .andExpect(jsonPath("$.reminders.length()").value(0))
                .andExpect(jsonPath("$.reminderOccurrences").isArray())
                .andExpect(jsonPath("$.reminderOccurrences.length()").value(0))
                .andExpect(jsonPath("$.checklistItems").isArray())
                .andExpect(jsonPath("$.checklistItems.length()").value(0))
                .andExpect(jsonPath("$.kickCountSessions").isArray())
                .andExpect(jsonPath("$.kickCountSessions.length()").value(0))
                .andExpect(jsonPath("$.consentHistory").isArray())
                .andExpect(jsonPath("$.consentHistory.length()").value(0));
    }

    // -------------------------------------------------------------------------
    // SECRETS — never in output
    // -------------------------------------------------------------------------

    /**
     * Verifies the raw JSON body does NOT contain any secret field name or material.
     * passwordHash, noteCipher, and password_hash must never appear.
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

    @Test
    void secretsNeverInOutput_noNoteCipher() throws Exception {
        // Seed a kick-count session WITH a noteCipher value
        KickCountSession session = buildKickCountSession(userA.getId());
        session.setNoteCipher(new byte[]{1, 2, 3, 4, 5}); // simulated cipher blob
        kickCountSessions.saveAndFlush(session);

        String body = mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("noteCipher")
                .doesNotContain("note_cipher");
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

        // User A's own supply item
        SupplyItem itemA = buildSupplyItem(userA.getId(), "UserA-item");
        supplyItems.saveAndFlush(itemA);

        // Export as user A
        String body = mvc.perform(get("/account/export")
                        .header("Authorization", bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").value("export-a@example.com"))
                .andExpect(jsonPath("$.supplyItems.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // A's export must not contain B's email or B's item name
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("UserA-item")
                .doesNotContain("export-b@example.com")
                .doesNotContain("UserB-item");
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

    @Test
    void kickCountSessionsIncluded_withoutNoteCipher() throws Exception {
        KickCountSession session = buildKickCountSession(userA.getId());
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
}
