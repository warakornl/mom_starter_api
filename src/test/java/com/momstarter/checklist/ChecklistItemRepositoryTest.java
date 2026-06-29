package com.momstarter.checklist;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
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
 * Repository-layer tests for {@link ChecklistItem} / {@link ChecklistItemRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 in PostgreSQL mode).
 * Mirrors {@code SupplyItemRepositoryTest}; adds appointment-specific invariants.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Client-generated id preserved on save.</li>
 *   <li>Save and retrieval via keyset pull query; {@code <sync>} columns populated.</li>
 *   <li>Keyset ordering: {@code (updated_at ASC, id ASC)}.</li>
 *   <li>Cursor continuation.</li>
 *   <li>Soft-delete tombstone: visible in pull, excluded from live list.</li>
 *   <li>{@code @Version} starts at {@code 0}, increments on update.</li>
 *   <li>{@code category} CHECK: all seven values accepted; unknown rejected.</li>
 *   <li>{@code source} CHECK: user_created/from_suggestion accepted; unknown rejected.</li>
 *   <li>{@code scheduled_at} is nullable (undated checklist tasks).</li>
 *   <li>{@code done} defaults to {@code false}; {@code done_at} is populated when done.</li>
 *   <li>{@link ChecklistItemRepository#findByUserIdAndIdIn} returns matching rows only.</li>
 *   <li>Live list excludes tombstones.</li>
 *   <li>Pull query isolates by userId.</li>
 *   <li>{@link ChecklistItemRepository#findUpcomingAppointments} returns open
 *       appointment/anc_visit items ordered by {@code scheduled_at ASC}.</li>
 *   <li>{@code note} is stored verbatim and round-trips (the R-A fold-point for
 *       location/doctor details, OQ-CAL-1).</li>
 *   <li>{@code source_suggestion_state_id} soft link persists and round-trips.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class ChecklistItemRepositoryTest {

    @Autowired
    private ChecklistItemRepository items;

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

    private ChecklistItem buildItem(UUID userId, String category, String title) {
        ChecklistItem c = new ChecklistItem();
        c.setId(UUID.randomUUID());   // client-generated
        c.setUserId(userId);
        c.setCategory(category);
        c.setTitle(title);
        return c;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * The client-generated UUID is preserved exactly on save.
     */
    @Test
    void clientSuppliedId_preserved_onSave() {
        User u = savedUser("cl-id@example.com");
        UUID clientId = UUID.fromString("11111111-2222-3333-4444-555555556666");

        ChecklistItem item = buildItem(u.getId(), "appointment", "OB Appointment");
        item.setId(clientId);
        item.setScheduledAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        ChecklistItem saved = items.saveAndFlush(item);

        assertThat(saved.getId()).isEqualTo(clientId);
        assertThat(items.findById(clientId)).isPresent();
    }

    /**
     * A saved item is returned by the pull keyset query with all {@code <sync>} columns
     * populated.
     */
    @Test
    void savesAndFinds_viaKeysetPullQuery() {
        User u = savedUser("cl-save@example.com");

        ChecklistItem item = buildItem(u.getId(), "anc_visit", "ANC Week 28");
        item.setScheduledAt(LocalDateTime.of(2026, 8, 14, 9, 30));
        item.setNote("ดร.สมหญิง คลินิกสุขภาพแม่");
        item.setSource("user_created");
        item.setClientId(UUID.randomUUID());
        items.saveAndFlush(item);

        List<ChecklistItem> found = items.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(found).hasSize(1);
        ChecklistItem f = found.get(0);
        assertThat(f.getTitle()).isEqualTo("ANC Week 28");
        assertThat(f.getCategory()).isEqualTo("anc_visit");
        assertThat(f.getScheduledAt()).isEqualTo(LocalDateTime.of(2026, 8, 14, 9, 30));
        assertThat(f.getNote()).isEqualTo("ดร.สมหญิง คลินิกสุขภาพแม่");
        assertThat(f.getSource()).isEqualTo("user_created");
        assertThat(f.isDone()).isFalse();
        assertThat(f.getDoneAt()).isNull();
        assertThat(f.getClientId()).isNotNull();
        assertThat(f.getCreatedAt()).isNotNull();
        assertThat(f.getUpdatedAt()).isNotNull();
        assertThat(f.getDeletedAt()).isNull();
        assertThat(f.getVersion()).isNotNull();
    }

    /**
     * Keyset ordering: {@code (updated_at ASC, id ASC)}.
     */
    @Test
    void keysetPull_ordersBy_updatedAt_thenId() {
        User u = savedUser("cl-order@example.com");

        ChecklistItem a = items.saveAndFlush(buildItem(u.getId(), "vaccine", "Flu shot"));
        ChecklistItem b = items.saveAndFlush(buildItem(u.getId(), "lab_panel", "CBC"));

        // Update A — now A has the latest updatedAt
        a.setTitle("Flu shot (booked)");
        a = items.saveAndFlush(a);

        assertThat(a.getUpdatedAt()).isAfterOrEqualTo(b.getUpdatedAt());

        List<ChecklistItem> found = items.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(found).hasSize(2);
        assertThat(found.get(found.size() - 1).getId()).isEqualTo(a.getId());
        found.forEach(c -> assertThat(c.getUserId()).isEqualTo(u.getId()));
    }

    /**
     * Cursor continuation resumes correctly.
     */
    @Test
    void cursorContinuation_resumesAfterGivenPosition() {
        User u = savedUser("cl-cursor@example.com");

        ChecklistItem a = items.saveAndFlush(buildItem(u.getId(), "checklist_task", "Pack bag A"));
        ChecklistItem b = items.saveAndFlush(buildItem(u.getId(), "checklist_task", "Pack bag B"));

        // Update A — latest updatedAt
        a.setTitle("Pack bag A (done)");
        a = items.saveAndFlush(a);

        List<ChecklistItem> afterCursor = items.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                b.getUpdatedAt(), b.getId(),
                Pageable.ofSize(100));

        UUID aId = a.getId();
        UUID bId = b.getId();
        assertThat(afterCursor).anyMatch(c -> c.getId().equals(aId));
        assertThat(afterCursor).noneMatch(c -> c.getId().equals(bId));
    }

    /**
     * Tombstone is visible in pull query, excluded from live list.
     */
    @Test
    void tombstone_visibleInPull_excludedFromLiveList() {
        User u = savedUser("cl-tombstone@example.com");

        ChecklistItem item = items.saveAndFlush(buildItem(u.getId(), "screening", "OGTT 75g"));
        item.setDeletedAt(Instant.now());
        items.saveAndFlush(item);

        List<ChecklistItem> pullResults = items.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(pullResults).hasSize(1);
        assertThat(pullResults.get(0).getDeletedAt()).isNotNull();

        List<ChecklistItem> liveResults = items.findByUserIdAndDeletedAtIsNull(u.getId(), Pageable.unpaged());
        assertThat(liveResults).isEmpty();
    }

    /**
     * {@code @Version} starts at {@code 0}, increments on update.
     */
    @Test
    void version_startsAtZero_incrementsOnUpdate() {
        User u = savedUser("cl-version@example.com");

        ChecklistItem item = items.saveAndFlush(buildItem(u.getId(), "postpartum_check", "6-week check"));
        assertThat(item.getVersion()).isEqualTo(0L);

        item.setDone(true);
        item.setDoneAt(Instant.now());
        item = items.saveAndFlush(item);
        assertThat(item.getVersion()).isEqualTo(1L);
    }

    /**
     * {@code category} CHECK accepts all seven values; rejects unknown.
     */
    @Test
    void category_checkConstraint_allValidValues_accepted() {
        User u = savedUser("cl-cat@example.com");

        for (String cat : List.of("appointment", "anc_visit", "lab_panel", "screening",
                "vaccine", "checklist_task", "postpartum_check")) {
            ChecklistItem c = buildItem(u.getId(), cat, "Title for " + cat);
            items.saveAndFlush(c);
        }

        List<ChecklistItem> found = items.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(found).hasSize(7);
    }

    @Test
    void category_unknownValue_rejectedByConstraint() {
        User u = savedUser("cl-badcat@example.com");

        ChecklistItem bad = buildItem(u.getId(), "UNKNOWN_CATEGORY", "Bad item");
        assertThatThrownBy(() -> {
            items.save(bad);
            items.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@code source} CHECK: valid values accepted; unknown rejected.
     */
    @Test
    void source_checkConstraint_validAndInvalid() {
        User u = savedUser("cl-source@example.com");

        ChecklistItem fromSuggestion = buildItem(u.getId(), "vaccine", "Flu shot");
        fromSuggestion.setSource("from_suggestion");
        fromSuggestion.setSourceSuggestionStateId(UUID.randomUUID());
        ChecklistItem saved = items.saveAndFlush(fromSuggestion);
        assertThat(saved.getSource()).isEqualTo("from_suggestion");

        ChecklistItem bad = buildItem(u.getId(), "vaccine", "Bad source");
        bad.setSource("UNKNOWN_SOURCE");
        assertThatThrownBy(() -> {
            items.save(bad);
            items.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@code scheduled_at} is nullable — undated checklist tasks (hospital-bag items) are valid.
     * This is the designed behaviour for checklist_task / postpartum_check categories
     * (data-model §3.4 — "nullable for undated checklists").
     */
    @Test
    void scheduledAt_nullable_forUndatedTasks() {
        User u = savedUser("cl-undated@example.com");

        ChecklistItem undated = buildItem(u.getId(), "checklist_task", "Buy car seat");
        undated.setScheduledAt(null);
        ChecklistItem saved = items.saveAndFlush(undated);

        assertThat(saved.getScheduledAt()).isNull();
    }

    /**
     * {@code done} defaults to {@code false}; marking done sets {@code done = true} and
     * {@code done_at} (absolute-UTC action instant).
     */
    @Test
    void done_defaultsFalse_markingDoneSetsTimestamp() {
        User u = savedUser("cl-done@example.com");

        ChecklistItem item = items.saveAndFlush(
                buildItem(u.getId(), "appointment", "Prenatal appointment"));
        assertThat(item.isDone()).isFalse();
        assertThat(item.getDoneAt()).isNull();

        // Mark done
        Instant doneAt = Instant.now();
        item.setDone(true);
        item.setDoneAt(doneAt);
        item = items.saveAndFlush(item);

        assertThat(item.isDone()).isTrue();
        assertThat(item.getDoneAt()).isNotNull();
    }

    /**
     * {@link ChecklistItemRepository#findByUserIdAndIdIn} returns matching rows only.
     */
    @Test
    void findByUserIdAndIdIn_returnsMatchingItems() {
        User u = savedUser("cl-batch@example.com");
        User other = savedUser("cl-other@example.com");

        ChecklistItem a = items.saveAndFlush(buildItem(u.getId(), "appointment", "Appointment A"));
        ChecklistItem b = items.saveAndFlush(buildItem(u.getId(), "screening", "Screening B"));
        ChecklistItem c = items.saveAndFlush(buildItem(other.getId(), "vaccine", "Other vaccine"));

        List<UUID> ids = List.of(a.getId(), b.getId(), c.getId());
        List<ChecklistItem> found = items.findByUserIdAndIdIn(u.getId(), ids);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(ChecklistItem::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    /**
     * Pull query isolates by userId.
     */
    @Test
    void pullQuery_isolatesResultsByUserId() {
        User alice = savedUser("alice-cl@example.com");
        User bob = savedUser("bob-cl@example.com");

        items.saveAndFlush(buildItem(alice.getId(), "appointment", "Alice appointment"));
        items.saveAndFlush(buildItem(bob.getId(), "vaccine", "Bob vaccine"));

        assertThat(items.findForPull(alice.getId(), Instant.EPOCH, Pageable.unpaged())).hasSize(1);
        assertThat(items.findForPull(bob.getId(), Instant.EPOCH, Pageable.unpaged())).hasSize(1);
    }

    /**
     * {@link ChecklistItemRepository#findUpcomingAppointments} returns upcoming open
     * appointment and anc_visit items ordered by {@code scheduled_at ASC} (OQ-CAL-8 — PINNED).
     * Excludes done items, tombstones, and non-appointment categories.
     */
    @Test
    void findUpcomingAppointments_returnsOpenAppointmentAndAncVisit_ordered() {
        User u = savedUser("cl-upcoming@example.com");

        LocalDateTime past = LocalDateTime.of(2026, 6, 1, 9, 0);
        LocalDateTime near = LocalDateTime.of(2026, 8, 1, 9, 0);
        LocalDateTime far  = LocalDateTime.of(2026, 9, 1, 9, 0);
        LocalDateTime since = LocalDateTime.of(2026, 7, 1, 0, 0);

        // Should appear (appointment, future, not done)
        ChecklistItem appt = buildItem(u.getId(), "appointment", "OB Week 32");
        appt.setScheduledAt(near);
        items.saveAndFlush(appt);

        // Should appear (anc_visit, future, not done)
        ChecklistItem anc = buildItem(u.getId(), "anc_visit", "ANC Week 36");
        anc.setScheduledAt(far);
        items.saveAndFlush(anc);

        // Should NOT appear — done=true
        ChecklistItem done = buildItem(u.getId(), "appointment", "Past appointment");
        done.setScheduledAt(near);
        done.setDone(true);
        done.setDoneAt(Instant.now());
        items.saveAndFlush(done);

        // Should NOT appear — past scheduled_at (before 'since')
        ChecklistItem pastItem = buildItem(u.getId(), "appointment", "Old appointment");
        pastItem.setScheduledAt(past);
        items.saveAndFlush(pastItem);

        // Should NOT appear — wrong category (screening)
        ChecklistItem screening = buildItem(u.getId(), "screening", "OGTT");
        screening.setScheduledAt(near);
        items.saveAndFlush(screening);

        // Should NOT appear — tombstoned
        ChecklistItem tombstoned = buildItem(u.getId(), "appointment", "Cancelled");
        tombstoned.setScheduledAt(near);
        tombstoned.setDeletedAt(Instant.now());
        items.saveAndFlush(tombstoned);

        List<ChecklistItem> upcoming = items.findUpcomingAppointments(u.getId(), since);

        assertThat(upcoming).hasSize(2);
        assertThat(upcoming.get(0).getScheduledAt()).isEqualTo(near);   // OB Week 32 (earlier)
        assertThat(upcoming.get(1).getScheduledAt()).isEqualTo(far);    // ANC Week 36 (later)
        assertThat(upcoming).extracting(ChecklistItem::getTitle)
                .containsExactly("OB Week 32", "ANC Week 36");
    }

    /**
     * {@code note} stores verbatim free-text and round-trips (the R-A fold-point for
     * location/doctor details — OQ-CAL-1 PINNED; G4 — NEVER parsed).
     */
    @Test
    void note_storedVerbatim_roundtrips() {
        User u = savedUser("cl-note@example.com");

        // The note folds location + doctor per R-A (OQ-CAL-1)
        String note = "สถานที่: โรงพยาบาลศิริราช\nแพทย์: นพ.สมชาย\nเบอร์: 02-419-7000";
        ChecklistItem item = buildItem(u.getId(), "appointment", "OB consultation");
        item.setScheduledAt(LocalDateTime.of(2026, 8, 10, 14, 0));
        item.setNote(note);
        items.saveAndFlush(item);

        ChecklistItem found = items.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged()).get(0);
        assertThat(found.getNote()).isEqualTo(note);
    }

    /**
     * {@code source_suggestion_state_id} (soft link — no FK) persists and round-trips.
     */
    @Test
    void sourceSuggestionStateId_softLink_persistsAndRoundtrips() {
        User u = savedUser("cl-suggestion@example.com");
        UUID suggestionStateId = UUID.randomUUID();

        ChecklistItem item = buildItem(u.getId(), "lab_panel", "Group B strep");
        item.setSource("from_suggestion");
        item.setSourceSuggestionStateId(suggestionStateId);
        items.saveAndFlush(item);

        ChecklistItem found = items.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged()).get(0);
        assertThat(found.getSourceSuggestionStateId()).isEqualTo(suggestionStateId);
        assertThat(found.getSource()).isEqualTo("from_suggestion");
    }
}
