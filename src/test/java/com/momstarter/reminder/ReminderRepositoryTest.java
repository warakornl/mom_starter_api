package com.momstarter.reminder;

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
 * Repository-layer tests for {@link Reminder} / {@link ReminderRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 in PostgreSQL mode,
 * {@code application-test.yml}). Mirrors the pattern in {@code SupplyItemRepositoryTest}.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Client-generated id is preserved on save (not overwritten by the server).</li>
 *   <li>Save and retrieval via the sync-pull keyset query; {@code <sync>} columns populated.</li>
 *   <li>Keyset ordering: {@code (updated_at ASC, id ASC)} — the pull/cursor invariant.</li>
 *   <li>Cursor continuation resumes after a given keyset position.</li>
 *   <li>Soft-delete tombstone: visible in pull query, excluded from live-list.</li>
 *   <li>{@code @Version} starts at {@code 0} on first persist, increments on update.</li>
 *   <li>{@code type} CHECK constraint rejects unknown values.</li>
 *   <li>{@code source_ref_type} CHECK accepts valid values and rejects unknown.</li>
 *   <li>{@link ReminderRepository#findByUserIdAndIdIn} returns only matching rows.</li>
 *   <li>Pull query isolates results by userId.</li>
 *   <li>{@code recurrence_rule} JSON string round-trips through the jsonb column.</li>
 *   <li>{@code start_at} (floating-civil {@code LocalDateTime}) round-trips correctly.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class ReminderRepositoryTest {

    @Autowired
    private ReminderRepository reminders;

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

    private Reminder buildReminder(UUID userId, String type, String displayTitle) {
        Reminder r = new Reminder();
        r.setId(UUID.randomUUID());   // client-generated
        r.setUserId(userId);
        r.setType(type);
        r.setDisplayTitle(displayTitle);
        r.setRecurrenceRule("{\"freq\":\"daily\",\"timesOfDay\":[\"08:00\"]}");
        r.setStartAt(LocalDateTime.of(2026, 7, 1, 8, 0));
        return r;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * The client-generated UUID is preserved exactly on save.
     * This is the core invariant for offline-safe push (sync spec §A.4 / data-model §2).
     */
    @Test
    void clientSuppliedId_preserved_onSave() {
        User u = savedUser("reminder-id@example.com");
        UUID clientId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        Reminder r = buildReminder(u.getId(), "medication", "Take iron supplement");
        r.setId(clientId);
        Reminder saved = reminders.saveAndFlush(r);

        assertThat(saved.getId()).isEqualTo(clientId);
        assertThat(reminders.findById(clientId)).isPresent();
    }

    /**
     * A saved reminder is returned by the sync-pull keyset query with all mandatory
     * {@code <sync>} columns populated.
     */
    @Test
    void savesAndFinds_viaKeysetPullQuery() {
        User u = savedUser("reminder-save@example.com");

        Reminder r = buildReminder(u.getId(), "appointment", "ANC Week 28");
        r.setSourceRefType("checklist_item");
        r.setSourceRefId(UUID.randomUUID());
        r.setActive(true);
        r.setClientId(UUID.randomUUID());
        reminders.saveAndFlush(r);

        List<Reminder> found = reminders.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(found).hasSize(1);
        Reminder f = found.get(0);
        assertThat(f.getDisplayTitle()).isEqualTo("ANC Week 28");
        assertThat(f.getType()).isEqualTo("appointment");
        assertThat(f.getSourceRefType()).isEqualTo("checklist_item");
        assertThat(f.getSourceRefId()).isNotNull();
        assertThat(f.isActive()).isTrue();
        assertThat(f.getClientId()).isNotNull();
        assertThat(f.getCreatedAt()).isNotNull();
        assertThat(f.getUpdatedAt()).isNotNull();
        assertThat(f.getDeletedAt()).isNull();
        assertThat(f.getVersion()).isNotNull();
    }

    /**
     * The pull keyset orders results by {@code (updated_at ASC, id ASC)}.
     * After updating A, B must come first (earlier updatedAt), A second (later updatedAt).
     */
    @Test
    void keysetPull_ordersBy_updatedAt_thenId() {
        User u = savedUser("reminder-order@example.com");

        Reminder a = reminders.saveAndFlush(buildReminder(u.getId(), "medication", "Morning vitamin"));
        Reminder b = reminders.saveAndFlush(buildReminder(u.getId(), "custom", "Evening walk"));

        // Update A — now A has the latest updatedAt
        a.setDisplayTitle("Morning vitamin D");
        a = reminders.saveAndFlush(a);

        assertThat(a.getUpdatedAt()).isAfterOrEqualTo(b.getUpdatedAt());

        List<Reminder> found = reminders.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(found).hasSize(2);
        // A (later updatedAt) must be last in ASC order
        assertThat(found.get(found.size() - 1).getId()).isEqualTo(a.getId());
        found.forEach(r -> assertThat(r.getUserId()).isEqualTo(u.getId()));
    }

    /**
     * Cursor continuation resumes correctly: items before or at the cursor position
     * are excluded; items after it are included.
     */
    @Test
    void cursorContinuation_resumesAfterGivenPosition() {
        User u = savedUser("reminder-cursor@example.com");

        Reminder a = reminders.saveAndFlush(buildReminder(u.getId(), "feeding", "Pump A"));
        Reminder b = reminders.saveAndFlush(buildReminder(u.getId(), "feeding", "Pump B"));

        // Update A so it has the latest updatedAt
        a.setDisplayTitle("Pump A revised");
        a = reminders.saveAndFlush(a);

        // Cursor at B → expect only A to follow
        List<Reminder> afterCursor = reminders.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                b.getUpdatedAt(), b.getId(),
                Pageable.ofSize(100));

        UUID aId = a.getId();
        UUID bId = b.getId();
        assertThat(afterCursor).anyMatch(r -> r.getId().equals(aId));
        assertThat(afterCursor).noneMatch(r -> r.getId().equals(bId));
    }

    /**
     * A tombstoned reminder is visible in the pull query (for propagation to other devices)
     * but is excluded from the live-list.
     */
    @Test
    void tombstone_visibleInPullQuery_excludedFromLiveList() {
        User u = savedUser("reminder-tombstone@example.com");

        Reminder r = reminders.saveAndFlush(buildReminder(u.getId(), "custom", "Walk 30 min"));
        r.setDeletedAt(Instant.now());
        reminders.saveAndFlush(r);

        List<Reminder> pullResults = reminders.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(pullResults).hasSize(1);
        assertThat(pullResults.get(0).getDeletedAt()).isNotNull();

        List<Reminder> liveResults = reminders.findByUserIdAndDeletedAtIsNull(u.getId(), Pageable.unpaged());
        assertThat(liveResults).isEmpty();
    }

    /**
     * {@code @Version} (Long wrapper) starts at {@code 0} on first persist and increments
     * on each update — server-assigned monotonic optimistic-concurrency token.
     */
    @Test
    void version_startsAtZero_incrementsOnUpdate() {
        User u = savedUser("reminder-version@example.com");

        Reminder r = reminders.saveAndFlush(buildReminder(u.getId(), "kick_count", "Daily kick count"));
        assertThat(r.getVersion()).isEqualTo(0L);

        r.setActive(false);
        r = reminders.saveAndFlush(r);
        assertThat(r.getVersion()).isEqualTo(1L);
    }

    /**
     * The DB CHECK on {@code type} rejects an unknown enum value.
     */
    @Test
    void type_unknownValue_rejectedByConstraint() {
        User u = savedUser("reminder-type@example.com");

        Reminder r = buildReminder(u.getId(), "INVALID_TYPE", "Bad type");

        assertThatThrownBy(() -> {
            reminders.save(r);
            reminders.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Valid {@code source_ref_type} values (medication_plan, checklist_item, supply_item)
     * are accepted; an unknown value is rejected by the DB CHECK.
     */
    @Test
    void sourceRefType_validValues_accepted_unknownRejected() {
        User u = savedUser("reminder-reftype@example.com");

        // Valid: checklist_item
        Reminder valid = buildReminder(u.getId(), "appointment", "Appointment reminder");
        valid.setSourceRefType("checklist_item");
        Reminder saved = reminders.saveAndFlush(valid);
        assertThat(saved.getSourceRefType()).isEqualTo("checklist_item");

        // null source_ref_type is also valid (reminder not linked to any entity)
        Reminder unlinked = buildReminder(u.getId(), "custom", "Custom standalone");
        unlinked.setSourceRefType(null);
        assertThat(reminders.saveAndFlush(unlinked).getSourceRefType()).isNull();

        // Invalid
        Reminder bad = buildReminder(u.getId(), "custom", "Bad ref type");
        bad.setSourceRefType("UNKNOWN_ENTITY");
        assertThatThrownBy(() -> {
            reminders.save(bad);
            reminders.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@link ReminderRepository#findByUserIdAndIdIn} returns only items matching the given
     * userId and id set — used by the sync/push apply path for batch version lookup.
     */
    @Test
    void findByUserIdAndIdIn_returnsMatchingItems() {
        User u = savedUser("reminder-batch@example.com");
        User other = savedUser("reminder-other@example.com");

        Reminder a = reminders.saveAndFlush(buildReminder(u.getId(), "medication", "Folic acid"));
        Reminder b = reminders.saveAndFlush(buildReminder(u.getId(), "appointment", "ANC visit"));
        Reminder c = reminders.saveAndFlush(buildReminder(other.getId(), "custom", "Other"));

        List<UUID> ids = List.of(a.getId(), b.getId(), c.getId());
        List<Reminder> found = reminders.findByUserIdAndIdIn(u.getId(), ids);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(Reminder::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    /**
     * Pull query returns items scoped to the requesting user only — data isolation.
     */
    @Test
    void pullQuery_isolatesResultsByUserId() {
        User alice = savedUser("alice-rem@example.com");
        User bob = savedUser("bob-rem@example.com");

        reminders.saveAndFlush(buildReminder(alice.getId(), "medication", "Alice folic acid"));
        reminders.saveAndFlush(buildReminder(bob.getId(), "custom", "Bob reminder"));

        List<Reminder> aliceItems = reminders.findForPull(alice.getId(), Instant.EPOCH, Pageable.unpaged());
        List<Reminder> bobItems = reminders.findForPull(bob.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(aliceItems).hasSize(1);
        assertThat(aliceItems.get(0).getDisplayTitle()).isEqualTo("Alice folic acid");
        assertThat(bobItems).hasSize(1);
        assertThat(bobItems.get(0).getDisplayTitle()).isEqualTo("Bob reminder");
    }

    /**
     * The {@code recurrence_rule} JSON string round-trips correctly through the
     * {@code jsonb} column (stored as text in H2 PostgreSQL mode).
     */
    @Test
    void recurrenceRule_jsonb_roundtrip() {
        User u = savedUser("reminder-jsonb@example.com");

        String rule = "{\"freq\":\"every_n_days\",\"interval\":2,\"timesOfDay\":[\"08:00\",\"20:00\"],\"until\":\"2026-12-31\"}";
        Reminder r = buildReminder(u.getId(), "medication", "Iron supplement");
        r.setRecurrenceRule(rule);
        reminders.saveAndFlush(r);

        Reminder found = reminders.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged()).get(0);
        assertThat(found.getRecurrenceRule()).isEqualTo(rule);
    }

    /**
     * The {@code start_at} floating-civil {@code LocalDateTime} round-trips correctly
     * through the {@code timestamp WITHOUT TIME ZONE} column.
     */
    @Test
    void startAt_localDateTime_roundtrip() {
        User u = savedUser("reminder-startat@example.com");

        LocalDateTime anchor = LocalDateTime.of(2026, 8, 15, 9, 30);
        Reminder r = buildReminder(u.getId(), "appointment", "ANC Week 32");
        r.setStartAt(anchor);
        reminders.saveAndFlush(r);

        Reminder found = reminders.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged()).get(0);
        assertThat(found.getStartAt()).isEqualTo(anchor);
    }

    /**
     * A {@code supply_restock} type reminder is valid (supplies slice carry-forward,
     * data-model §3.9 / §5).
     */
    @Test
    void supplyRestockType_isValidEnum() {
        User u = savedUser("reminder-supply@example.com");

        Reminder r = buildReminder(u.getId(), "supply_restock", "Restock diapers");
        r.setSourceRefType("supply_item");
        r.setSourceRefId(UUID.randomUUID());
        Reminder saved = reminders.saveAndFlush(r);

        assertThat(saved.getType()).isEqualTo("supply_restock");
        assertThat(saved.getSourceRefType()).isEqualTo("supply_item");
    }
}
