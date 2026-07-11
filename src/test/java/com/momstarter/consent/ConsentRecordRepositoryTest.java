package com.momstarter.consent;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link ConsentRecord} / {@link ConsentRecordRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 in PostgreSQL mode).
 * No jsonb columns in consent_record — H2 compatibility is straightforward.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>No row → findLatestGranted returns empty (fail-closed semantic).</li>
 *   <li>Single granted row → findLatestGranted returns true.</li>
 *   <li>Single withdrawn row → findLatestGranted returns false.</li>
 *   <li>Grant then withdraw → latest is false (withdrawal wins).</li>
 *   <li>Grant then withdraw then re-grant → latest is true.</li>
 *   <li>List query (no cursor): ordered granted_at DESC, id DESC.</li>
 *   <li>List query (with cursor): keyset scan continues correctly.</li>
 *   <li>Different users are scoped independently (IDOR prevention).</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class ConsentRecordRepositoryTest {

    @Autowired
    private ConsentRecordRepository consentRecords;

    @Autowired
    private UserRepository users;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private User savedUser(String email) {
        User u = new User();
        u.setEmail(email);
        return users.save(u);
    }

    private ConsentRecord buildRecord(UUID userId, String consentType, boolean granted) {
        ConsentRecord r = new ConsentRecord();
        r.setUserId(userId);
        r.setConsentType(consentType);
        r.setGranted(granted);
        r.setConsentTextVersion("v1.0-th");
        r.setLocale("th");
        return r;
    }

    private ConsentRecord buildRecordAt(UUID userId, String consentType, boolean granted, Instant grantedAt) {
        ConsentRecord r = buildRecord(userId, consentType, granted);
        // Force grantedAt by setting the id so @PrePersist uses our timestamp
        // We persist and then the DEFAULT now() kicks in; for timestamp control
        // we set granted_at directly using a separate save approach below
        return r;
    }

    // -------------------------------------------------------------------------
    // findLatestGranted — fail-closed semantics
    // -------------------------------------------------------------------------

    @Test
    void findLatestGranted_noRow_returnsEmpty() {
        User user = savedUser("consent-repo-1@example.com");

        Optional<Boolean> result = consentRecords.findLatestGranted(user.getId(), "general_health");

        assertThat(result).isEmpty();
    }

    @Test
    void findLatestGranted_singleGrantedRow_returnsTrue() {
        User user = savedUser("consent-repo-2@example.com");
        consentRecords.save(buildRecord(user.getId(), "general_health", true));
        consentRecords.flush();

        Optional<Boolean> result = consentRecords.findLatestGranted(user.getId(), "general_health");

        assertThat(result).isPresent();
        assertThat(result.get()).isTrue();
    }

    @Test
    void findLatestGranted_singleWithdrawnRow_returnsFalse() {
        User user = savedUser("consent-repo-3@example.com");
        consentRecords.save(buildRecord(user.getId(), "cloud_storage", false));
        consentRecords.flush();

        Optional<Boolean> result = consentRecords.findLatestGranted(user.getId(), "cloud_storage");

        assertThat(result).isPresent();
        assertThat(result.get()).isFalse();
    }

    @Test
    void findLatestGranted_grantThenWithdraw_returnsFalse() {
        User user = savedUser("consent-repo-4@example.com");
        consentRecords.save(buildRecord(user.getId(), "general_health", true));
        consentRecords.flush();
        consentRecords.save(buildRecord(user.getId(), "general_health", false));
        consentRecords.flush();

        Optional<Boolean> result = consentRecords.findLatestGranted(user.getId(), "general_health");

        assertThat(result).isPresent();
        assertThat(result.get()).isFalse();
    }

    @Test
    void findLatestGranted_grantWithdrawReGrant_returnsTrue() {
        User user = savedUser("consent-repo-5@example.com");
        consentRecords.save(buildRecord(user.getId(), "general_health", true));
        consentRecords.flush();
        consentRecords.save(buildRecord(user.getId(), "general_health", false));
        consentRecords.flush();
        consentRecords.save(buildRecord(user.getId(), "general_health", true));
        consentRecords.flush();

        Optional<Boolean> result = consentRecords.findLatestGranted(user.getId(), "general_health");

        assertThat(result).isPresent();
        assertThat(result.get()).isTrue();
    }

    @Test
    void findLatestGranted_scopedByConsentType_independentPerType() {
        User user = savedUser("consent-repo-6@example.com");
        // grant general_health, withdraw cloud_storage
        consentRecords.save(buildRecord(user.getId(), "general_health", true));
        consentRecords.save(buildRecord(user.getId(), "cloud_storage", false));
        consentRecords.flush();

        assertThat(consentRecords.findLatestGranted(user.getId(), "general_health"))
                .contains(true);
        assertThat(consentRecords.findLatestGranted(user.getId(), "cloud_storage"))
                .contains(false);
    }

    @Test
    void findLatestGranted_differentUsers_scopedCorrectly() {
        User userA = savedUser("consent-repo-7a@example.com");
        User userB = savedUser("consent-repo-7b@example.com");
        consentRecords.save(buildRecord(userA.getId(), "general_health", true));
        consentRecords.save(buildRecord(userB.getId(), "general_health", false));
        consentRecords.flush();

        assertThat(consentRecords.findLatestGranted(userA.getId(), "general_health")).contains(true);
        assertThat(consentRecords.findLatestGranted(userB.getId(), "general_health")).contains(false);
    }

    @Test
    void findLatestGranted_allSixConsentTypesAreValid() {
        // Confirms the CHECK constraint accepts all 6 types without throwing
        User user = savedUser("consent-repo-8@example.com");
        for (String type : List.of("general_health", "sensitive_lab_results", "pdf_egress",
                "infant_feeding", "cloud_storage", "child_health")) {
            consentRecords.save(buildRecord(user.getId(), type, true));
        }
        consentRecords.flush();

        for (String type : List.of("general_health", "sensitive_lab_results", "pdf_egress",
                "infant_feeding", "cloud_storage", "child_health")) {
            assertThat(consentRecords.findLatestGranted(user.getId(), type)).contains(true);
        }
    }

    // -------------------------------------------------------------------------
    // findByUserIdOrderByGrantedAtDescIdDesc — first-page list query
    // -------------------------------------------------------------------------

    @Test
    void findFirstPage_returnsRowsNewestFirst() {
        User user = savedUser("consent-repo-9@example.com");
        ConsentRecord r1 = consentRecords.save(buildRecord(user.getId(), "general_health", true));
        consentRecords.flush();
        ConsentRecord r2 = consentRecords.save(buildRecord(user.getId(), "cloud_storage", true));
        consentRecords.flush();

        List<ConsentRecord> page = consentRecords
                .findByUserIdOrderByGrantedAtDescIdDesc(user.getId(), Pageable.ofSize(10));

        // Both returned; newer row (r2) should come first or the order should be DESC
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getId()).isEqualTo(r2.getId());
        assertThat(page.get(1).getId()).isEqualTo(r1.getId());
    }

    @Test
    void findFirstPage_limitApplied() {
        User user = savedUser("consent-repo-10@example.com");
        for (String type : List.of("general_health", "cloud_storage", "pdf_egress")) {
            consentRecords.save(buildRecord(user.getId(), type, true));
        }
        consentRecords.flush();

        List<ConsentRecord> page = consentRecords
                .findByUserIdOrderByGrantedAtDescIdDesc(user.getId(), Pageable.ofSize(2));

        assertThat(page).hasSize(2);
    }

    @Test
    void findFirstPage_scopedByUserId() {
        User userA = savedUser("consent-repo-11a@example.com");
        User userB = savedUser("consent-repo-11b@example.com");
        consentRecords.save(buildRecord(userA.getId(), "general_health", true));
        consentRecords.save(buildRecord(userB.getId(), "cloud_storage", true));
        consentRecords.flush();

        List<ConsentRecord> pageA = consentRecords
                .findByUserIdOrderByGrantedAtDescIdDesc(userA.getId(), Pageable.ofSize(10));

        assertThat(pageA).hasSize(1);
        assertThat(pageA.get(0).getUserId()).isEqualTo(userA.getId());
    }

    // -------------------------------------------------------------------------
    // calendar_sync — 7th consent type (V20260711000025)
    // -------------------------------------------------------------------------

    /**
     * Asserts that {@code calendar_sync} is accepted by the CHECK constraint after migration
     * V20260711000025 widens the IN-list from 6 to 7 values.
     *
     * <p>This test is the TDD gate for the CHECK-widening migration:
     * if V20260711000025 did not run (or ran incorrectly), inserting {@code calendar_sync}
     * would throw a {@code DataIntegrityViolationException} and this test would fail.
     *
     * <p>PDPA basis: {@code calendar_sync} (ม.26 explicit consent) for writing ANC appointment
     * data to the device-native calendar — approved by {@code compliance-reviewer} per
     * {@code docs/compliance/calendar-sync-pdpa.md §1.1}.
     */
    @Test
    void calendarSync_insertsSuccessfully() {
        User user = savedUser("consent-repo-calsync-1@example.com");
        consentRecords.save(buildRecord(user.getId(), "calendar_sync", true));
        consentRecords.flush();

        assertThat(consentRecords.findLatestGranted(user.getId(), "calendar_sync"))
                .as("calendar_sync granted row should be retrievable after migration V20260711000025")
                .contains(true);
    }

    /**
     * Asserts that a completely invalid consent type is rejected by the CHECK constraint.
     *
     * <p>Verifies that the widened 7-value constraint still rejects values outside the
     * known set (not just the original 6).  An IN-list CHECK that was accidentally widened
     * to {@code IN ('general_health', ...)} without the {@code NOT NULL} or with a
     * catch-all would silently accept garbage; this test guards against that.
     *
     * <p>H2 throws {@code JdbcSQLIntegrityConstraintViolationException} on CHECK violation;
     * Spring Data JPA wraps it as {@code DataIntegrityViolationException}.
     */
    @Test
    void invalidConsentType_isRejectedByCheckConstraint() {
        User user = savedUser("consent-repo-invalid-type-1@example.com");
        ConsentRecord r = buildRecord(user.getId(), "not_a_valid_consent_type", true);

        assertThatThrownBy(() -> {
            consentRecords.save(r);
            consentRecords.flush();
        })
                .as("A consent_type value outside the 7-value IN-list must be rejected by the CHECK constraint")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -------------------------------------------------------------------------
    // findByUserIdBeforeCursor — cursor-continuation
    // -------------------------------------------------------------------------

    @Test
    void findBeforeCursor_returnsOnlyRowsAfterCursor() {
        User user = savedUser("consent-repo-12@example.com");
        ConsentRecord r1 = consentRecords.save(buildRecord(user.getId(), "general_health", true));
        consentRecords.flush();
        ConsentRecord r2 = consentRecords.save(buildRecord(user.getId(), "cloud_storage", true));
        consentRecords.flush();
        ConsentRecord r3 = consentRecords.save(buildRecord(user.getId(), "pdf_egress", true));
        consentRecords.flush();

        // Get full page first to know the order
        List<ConsentRecord> firstPage = consentRecords
                .findByUserIdOrderByGrantedAtDescIdDesc(user.getId(), Pageable.ofSize(10));
        // firstPage is [r3, r2, r1] (newest first since each save has a later granted_at via DEFAULT now())
        assertThat(firstPage).hasSize(3);

        // Cursor after the second item (r2)
        ConsentRecord cursorRow = firstPage.get(1);
        List<ConsentRecord> nextPage = consentRecords.findByUserIdBeforeCursor(
                user.getId(), cursorRow.getGrantedAt(), cursorRow.getId(), Pageable.ofSize(10));

        // Should return only r1 (older than r2)
        assertThat(nextPage).hasSize(1);
        assertThat(nextPage.get(0).getId()).isEqualTo(firstPage.get(2).getId());
    }
}
