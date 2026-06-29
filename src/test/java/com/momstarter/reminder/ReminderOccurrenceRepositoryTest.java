package com.momstarter.reminder;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.occurrence.OccurrenceId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link ReminderOccurrence} / {@link ReminderOccurrenceRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 in PostgreSQL mode).
 * Mirrors {@code SupplyItemRepositoryTest}; adds occurrence-specific invariants:
 * deterministic id, minute-precision, natural-key uniqueness, soft-link tolerance.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Client-computed deterministic uuidv5 id preserved on save.</li>
 *   <li>Save and retrieval; {@code <sync>} columns populated.</li>
 *   <li>Keyset ordering: {@code (updated_at ASC, id ASC)}.</li>
 *   <li>Cursor continuation.</li>
 *   <li>Soft-delete tombstone: visible in pull query, excluded from live list.</li>
 *   <li>{@code @Version} starts at {@code 0}, increments on update.</li>
 *   <li>{@code status} CHECK: all four values accepted; unknown rejected.</li>
 *   <li>Natural-key uniqueness: duplicate {@code (user_id, reminder_id, scheduled_local_time)}
 *       rejected.</li>
 *   <li>Minute-precision CHECK: non-zero second rejected
 *       ({@code ck_reminder_occurrences__minute}).</li>
 *   <li>Soft-link tolerance: {@code reminder_id} may reference a tombstoned Reminder
 *       (OQ-CAL-6 — orphan retention).</li>
 *   <li>Adherence-history query ({@link ReminderOccurrenceRepository#findLiveByUserIdAndReminderId})
 *       returns live rows ordered by {@code scheduled_local_time ASC}.</li>
 *   <li>{@link ReminderOccurrenceRepository#findByUserIdAndIdIn} returns matching rows only.</li>
 *   <li>Pull query isolates by userId.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class ReminderOccurrenceRepositoryTest {

    @Autowired
    private ReminderOccurrenceRepository occurrences;

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

    /**
     * Builds an occurrence with a deterministic uuidv5 id computed from
     * {@code (reminderId, scheduledLocalCivil)} via {@link OccurrenceId#compute},
     * mirroring the client and server's shared algorithm.
     */
    private ReminderOccurrence buildOccurrence(UUID userId,
                                                UUID reminderId,
                                                LocalDateTime scheduledLocalTime,
                                                String status) {
        String civil = String.format("%04d-%02d-%02dT%02d:%02d",
                scheduledLocalTime.getYear(),
                scheduledLocalTime.getMonthValue(),
                scheduledLocalTime.getDayOfMonth(),
                scheduledLocalTime.getHour(),
                scheduledLocalTime.getMinute());

        UUID deterministicId = OccurrenceId.compute(reminderId.toString(), civil);

        ReminderOccurrence o = new ReminderOccurrence();
        o.setId(deterministicId);
        o.setUserId(userId);
        o.setReminderId(reminderId);
        o.setScheduledLocalTime(scheduledLocalTime);
        o.setStatus(status);
        return o;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * The client-computed deterministic uuidv5 id is preserved exactly on save.
     * Core invariant: same (reminderId, civil-datetime) → ONE id on every device.
     */
    @Test
    void deterministicId_preserved_onSave() {
        User u = savedUser("occ-id@example.com");
        UUID reminderId = UUID.randomUUID();
        LocalDateTime civil = LocalDateTime.of(2026, 7, 1, 8, 0);
        String civilStr = "2026-07-01T08:00";

        UUID expectedId = OccurrenceId.compute(reminderId.toString(), civilStr);

        ReminderOccurrence occ = buildOccurrence(u.getId(), reminderId, civil, "done");
        occ.setActedAt(Instant.now());
        ReminderOccurrence saved = occurrences.saveAndFlush(occ);

        assertThat(saved.getId()).isEqualTo(expectedId);
        assertThat(occurrences.findById(expectedId)).isPresent();
    }

    /**
     * A saved occurrence is returned by the pull keyset query with all {@code <sync>}
     * columns populated.
     */
    @Test
    void savesAndFinds_viaKeysetPullQuery() {
        User u = savedUser("occ-save@example.com");
        UUID reminderId = UUID.randomUUID();
        LocalDateTime civil = LocalDateTime.of(2026, 7, 2, 9, 0);

        ReminderOccurrence occ = buildOccurrence(u.getId(), reminderId, civil, "done");
        occ.setActedAt(Instant.now());
        occ.setClientId(UUID.randomUUID());
        occurrences.saveAndFlush(occ);

        List<ReminderOccurrence> found = occurrences.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(found).hasSize(1);
        ReminderOccurrence f = found.get(0);
        assertThat(f.getReminderId()).isEqualTo(reminderId);
        assertThat(f.getScheduledLocalTime()).isEqualTo(civil);
        assertThat(f.getStatus()).isEqualTo("done");
        assertThat(f.getActedAt()).isNotNull();
        assertThat(f.getClientId()).isNotNull();
        assertThat(f.getCreatedAt()).isNotNull();
        assertThat(f.getUpdatedAt()).isNotNull();
        assertThat(f.getDeletedAt()).isNull();
        assertThat(f.getVersion()).isNotNull();
    }

    /**
     * Keyset ordering: {@code (updated_at ASC, id ASC)}.
     * After updating occurrence A, B (earlier updatedAt) must come first.
     */
    @Test
    void keysetPull_ordersBy_updatedAt_thenId() {
        User u = savedUser("occ-order@example.com");
        UUID reminderId = UUID.randomUUID();

        ReminderOccurrence a = occurrences.saveAndFlush(
                buildOccurrence(u.getId(), reminderId, LocalDateTime.of(2026, 7, 1, 8, 0), "done"));
        ReminderOccurrence b = occurrences.saveAndFlush(
                buildOccurrence(u.getId(), reminderId, LocalDateTime.of(2026, 7, 2, 8, 0), "snoozed"));

        // Update A — now A has the latest updatedAt
        a.setStatus("done");
        a.setActedAt(Instant.now());
        a = occurrences.saveAndFlush(a);

        assertThat(a.getUpdatedAt()).isAfterOrEqualTo(b.getUpdatedAt());

        List<ReminderOccurrence> found = occurrences.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(found).hasSize(2);
        assertThat(found.get(found.size() - 1).getId()).isEqualTo(a.getId());
    }

    /**
     * Cursor continuation resumes after the given position.
     */
    @Test
    void cursorContinuation_resumesAfterGivenPosition() {
        User u = savedUser("occ-cursor@example.com");
        UUID reminderId = UUID.randomUUID();

        ReminderOccurrence a = occurrences.saveAndFlush(
                buildOccurrence(u.getId(), reminderId, LocalDateTime.of(2026, 7, 1, 8, 0), "done"));
        ReminderOccurrence b = occurrences.saveAndFlush(
                buildOccurrence(u.getId(), reminderId, LocalDateTime.of(2026, 7, 2, 8, 0), "snoozed"));

        // Update A — latest updatedAt
        a.setActedAt(Instant.now());
        a = occurrences.saveAndFlush(a);

        // Cursor at B → only A should follow
        List<ReminderOccurrence> afterCursor = occurrences.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                b.getUpdatedAt(), b.getId(),
                Pageable.ofSize(100));

        UUID aId = a.getId();
        UUID bId = b.getId();
        assertThat(afterCursor).anyMatch(o -> o.getId().equals(aId));
        assertThat(afterCursor).noneMatch(o -> o.getId().equals(bId));
    }

    /**
     * Tombstone is visible in pull query (propagates deletion) but excluded from live list.
     */
    @Test
    void tombstone_visibleInPull_excludedFromLiveList() {
        User u = savedUser("occ-tombstone@example.com");
        UUID reminderId = UUID.randomUUID();

        ReminderOccurrence occ = occurrences.saveAndFlush(
                buildOccurrence(u.getId(), reminderId, LocalDateTime.of(2026, 7, 1, 8, 0), "done"));
        occ.setDeletedAt(Instant.now());
        occurrences.saveAndFlush(occ);

        List<ReminderOccurrence> pullResults = occurrences.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(pullResults).hasSize(1);
        assertThat(pullResults.get(0).getDeletedAt()).isNotNull();

        List<ReminderOccurrence> liveResults = occurrences.findByUserIdAndDeletedAtIsNull(u.getId(), Pageable.unpaged());
        assertThat(liveResults).isEmpty();
    }

    /**
     * {@code @Version} starts at {@code 0}, increments on update.
     */
    @Test
    void version_startsAtZero_incrementsOnUpdate() {
        User u = savedUser("occ-version@example.com");
        UUID reminderId = UUID.randomUUID();

        ReminderOccurrence occ = occurrences.saveAndFlush(
                buildOccurrence(u.getId(), reminderId, LocalDateTime.of(2026, 7, 1, 8, 0), "done"));
        assertThat(occ.getVersion()).isEqualTo(0L);

        occ.setActedAt(Instant.now());
        occ = occurrences.saveAndFlush(occ);
        assertThat(occ.getVersion()).isEqualTo(1L);
    }

    /**
     * All four status values are accepted by the DB CHECK; unknown value is rejected.
     */
    @Test
    void status_checkConstraint_acceptsValidValues_rejectsUnknown() {
        User u = savedUser("occ-status@example.com");
        UUID remId = UUID.randomUUID();

        // All four valid statuses
        for (String status : List.of("due", "done", "snoozed", "missed")) {
            LocalDateTime civil = LocalDateTime.of(2026, 7, 1 + List.of("due", "done", "snoozed", "missed").indexOf(status), 8, 0);
            occurrences.saveAndFlush(buildOccurrence(u.getId(), remId, civil, status));
        }

        List<ReminderOccurrence> found = occurrences.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(found).hasSize(4);
        assertThat(found).extracting(ReminderOccurrence::getStatus)
                .containsExactlyInAnyOrder("due", "done", "snoozed", "missed");

        // Invalid status
        ReminderOccurrence bad = buildOccurrence(u.getId(), remId, LocalDateTime.of(2026, 7, 10, 8, 0), "INVALID");
        assertThatThrownBy(() -> {
            occurrences.save(bad);
            occurrences.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Natural-key uniqueness ({@code uq_reminder_occurrences__natural}):
     * two occurrences with the same {@code (user_id, reminder_id, scheduled_local_time)}
     * are rejected even if the ids are different.
     */
    @Test
    void naturalKeyUniqueness_preventsDuplicate() {
        User u = savedUser("occ-naturalkey@example.com");
        UUID reminderId = UUID.randomUUID();
        LocalDateTime civil = LocalDateTime.of(2026, 7, 1, 8, 0);

        // First occurrence with the deterministic id
        occurrences.saveAndFlush(buildOccurrence(u.getId(), reminderId, civil, "done"));

        // Second occurrence: same (userId, reminderId, scheduledLocalTime) but a DIFFERENT id
        // (simulates a buggy client that mis-derived the id)
        ReminderOccurrence duplicate = new ReminderOccurrence();
        duplicate.setId(UUID.randomUUID());  // different id — intentionally wrong
        duplicate.setUserId(u.getId());
        duplicate.setReminderId(reminderId);
        duplicate.setScheduledLocalTime(civil);
        duplicate.setStatus("done");

        assertThatThrownBy(() -> {
            occurrences.save(duplicate);
            occurrences.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Minute-precision CHECK ({@code ck_reminder_occurrences__minute}):
     * {@code scheduled_local_time} with non-zero seconds is rejected.
     * The uuidv5 derivation uses minute precision only ("YYYY-MM-DDTHH:mm").
     */
    @Test
    void minutePrecision_nonZeroSecond_rejectedByConstraint() {
        User u = savedUser("occ-minute@example.com");
        UUID reminderId = UUID.randomUUID();

        // Second = 30 → violates ck_reminder_occurrences__minute
        LocalDateTime withSeconds = LocalDateTime.of(2026, 7, 1, 8, 0, 30);

        ReminderOccurrence bad = new ReminderOccurrence();
        bad.setId(UUID.randomUUID());
        bad.setUserId(u.getId());
        bad.setReminderId(reminderId);
        bad.setScheduledLocalTime(withSeconds);
        bad.setStatus("done");

        assertThatThrownBy(() -> {
            occurrences.save(bad);
            occurrences.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Soft-link tolerance (OQ-CAL-6): a {@code reminder_id} that references no
     * existing {@code reminders} row is accepted (orphan-tolerant, no FK).
     * Past done/snoozed rows are retained as adherence history even after the
     * parent Reminder is tombstoned.
     */
    @Test
    void softLink_noFk_toleratesOrphanReminderId() {
        User u = savedUser("occ-orphan@example.com");

        // reminderId that does NOT correspond to any row in the reminders table
        UUID orphanReminderId = UUID.randomUUID();

        ReminderOccurrence occ = buildOccurrence(u.getId(), orphanReminderId,
                LocalDateTime.of(2026, 7, 1, 8, 0), "done");
        // Should save without error (no FK to enforce)
        ReminderOccurrence saved = occurrences.saveAndFlush(occ);
        assertThat(saved.getReminderId()).isEqualTo(orphanReminderId);

        // The occurrence is still retrievable (adherence history)
        List<ReminderOccurrence> found = occurrences.findLiveByUserIdAndReminderId(u.getId(), orphanReminderId);
        assertThat(found).hasSize(1);
    }

    /**
     * {@link ReminderOccurrenceRepository#findLiveByUserIdAndReminderId} returns live rows
     * ordered by {@code scheduled_local_time ASC}, excluding tombstones.
     */
    @Test
    void findLiveByUserIdAndReminderId_returnsSortedLiveRows() {
        User u = savedUser("occ-history@example.com");
        UUID reminderId = UUID.randomUUID();

        LocalDateTime t1 = LocalDateTime.of(2026, 7, 1, 8, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 7, 2, 8, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 7, 3, 8, 0);

        ReminderOccurrence occ1 = occurrences.saveAndFlush(buildOccurrence(u.getId(), reminderId, t1, "done"));
        occurrences.saveAndFlush(buildOccurrence(u.getId(), reminderId, t2, "snoozed"));
        ReminderOccurrence occ3 = occurrences.saveAndFlush(buildOccurrence(u.getId(), reminderId, t3, "done"));

        // Tombstone occ3
        occ3.setDeletedAt(Instant.now());
        occurrences.saveAndFlush(occ3);

        List<ReminderOccurrence> history = occurrences.findLiveByUserIdAndReminderId(u.getId(), reminderId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getScheduledLocalTime()).isEqualTo(t1);
        assertThat(history.get(1).getScheduledLocalTime()).isEqualTo(t2);
    }

    /**
     * {@link ReminderOccurrenceRepository#findByUserIdAndIdIn} returns matching rows only,
     * scoped to the given user.
     */
    @Test
    void findByUserIdAndIdIn_returnsMatchingItems() {
        User u = savedUser("occ-batch@example.com");
        User other = savedUser("occ-other@example.com");
        UUID remId = UUID.randomUUID();

        ReminderOccurrence a = occurrences.saveAndFlush(
                buildOccurrence(u.getId(), remId, LocalDateTime.of(2026, 7, 1, 8, 0), "done"));
        ReminderOccurrence b = occurrences.saveAndFlush(
                buildOccurrence(u.getId(), remId, LocalDateTime.of(2026, 7, 2, 8, 0), "snoozed"));
        ReminderOccurrence c = occurrences.saveAndFlush(
                buildOccurrence(other.getId(), remId, LocalDateTime.of(2026, 7, 3, 8, 0), "done"));

        List<UUID> ids = List.of(a.getId(), b.getId(), c.getId());
        List<ReminderOccurrence> found = occurrences.findByUserIdAndIdIn(u.getId(), ids);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(ReminderOccurrence::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    /**
     * Pull query isolates results by userId — data isolation invariant.
     */
    @Test
    void pullQuery_isolatesResultsByUserId() {
        User alice = savedUser("alice-occ@example.com");
        User bob = savedUser("bob-occ@example.com");
        UUID remId = UUID.randomUUID();

        occurrences.saveAndFlush(buildOccurrence(alice.getId(), remId, LocalDateTime.of(2026, 7, 1, 8, 0), "done"));
        occurrences.saveAndFlush(buildOccurrence(bob.getId(), remId, LocalDateTime.of(2026, 7, 1, 9, 0), "snoozed"));

        assertThat(occurrences.findForPull(alice.getId(), Instant.EPOCH, Pageable.unpaged())).hasSize(1);
        assertThat(occurrences.findForPull(bob.getId(), Instant.EPOCH, Pageable.unpaged())).hasSize(1);
    }
}
