package com.momstarter.supply;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link SupplyItem} / {@link SupplyItemRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 in PostgreSQL mode,
 * {@code application-test.yml}). Mirrors the pattern in
 * {@code PregnancyProfileRepositoryTest} and {@code AuthIdentityRepositoryTest}.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Client-generated id is preserved on save (not overwritten by the server).</li>
 *   <li>Basic save and retrieval via the sync-pull keyset query.</li>
 *   <li>Keyset ordering: {@code (updated_at ASC, id ASC)} — the pull/cursor invariant.</li>
 *   <li>Soft-delete tombstone: {@code deleted_at} is set and the row is still accessible
 *       to the pull query (tombstone propagation) but excluded from live-list reads.</li>
 *   <li>{@code @Version} starts at 0 on first persist, increments on update.</li>
 *   <li>DB {@code CHECK (on_hand_qty >= 0)} rejects a negative quantity.</li>
 *   <li>{@link SupplyItemRepository#findByUserIdAndIdIn} returns matching rows only.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class SupplyItemRepositoryTest {

    @Autowired
    private SupplyItemRepository items;

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
     * Builds a new (unsaved) SupplyItem with a client-generated UUID already set.
     * The {@code id} is client-supplied — no server generation.
     */
    private SupplyItem buildItem(UUID userId, String name, String category) {
        SupplyItem item = new SupplyItem();
        item.setId(UUID.randomUUID());   // client-generated uuid — set before save
        item.setUserId(userId);
        item.setName(name);
        item.setCategory(category);
        return item;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * The client-generated UUID is preserved exactly on save — the server must NEVER
     * replace or regenerate it. This is the core invariant for offline-safe push
     * (sync spec §A.4 / data-model §2).
     */
    @Test
    void clientSuppliedId_preserved_onSave() {
        User u = savedUser("supply-id@example.com");
        UUID clientId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        SupplyItem item = buildItem(u.getId(), "Diapers NB", "diapers");
        item.setId(clientId);
        SupplyItem saved = items.saveAndFlush(item);

        assertThat(saved.getId()).isEqualTo(clientId);
        assertThat(items.findById(clientId)).isPresent();
    }

    /**
     * A saved item is returned by the sync-pull keyset query and has all mandatory
     * {@code <sync>} columns populated.
     */
    @Test
    void savesAndFinds_viaKeysetPullQuery() {
        User u = savedUser("supply-save@example.com");

        SupplyItem item = buildItem(u.getId(), "Baby Wipes", "hygiene");
        item.setUnit("pack");
        item.setOnHandQty(3);
        item.setLowThreshold(2);
        item.setClientId(UUID.randomUUID());
        items.saveAndFlush(item);

        List<SupplyItem> found = items.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(found).hasSize(1);
        SupplyItem f = found.get(0);
        assertThat(f.getName()).isEqualTo("Baby Wipes");
        assertThat(f.getCategory()).isEqualTo("hygiene");
        assertThat(f.getUnit()).isEqualTo("pack");
        assertThat(f.getOnHandQty()).isEqualTo(3);
        assertThat(f.getLowThreshold()).isEqualTo(2);
        assertThat(f.getClientId()).isNotNull();
        assertThat(f.getCreatedAt()).isNotNull();
        assertThat(f.getUpdatedAt()).isNotNull();
        assertThat(f.getDeletedAt()).isNull();        // live row
        assertThat(f.getVersion()).isNotNull();       // version stamped by Hibernate on insert
    }

    /**
     * The pull keyset orders results by {@code (updated_at ASC, id ASC)}.
     *
     * <p>Procedure: save item A, save item B, then update item A. After the update,
     * A has a newer {@code updated_at} than B (which was saved second but not updated).
     * The pull must return B first, then A.
     *
     * <p>This validates the {@code ix_supply_items__sync_pull (user_id, updated_at, id)}
     * keyset invariant that the cold-start cursor depends on (sync spec §B.4 / database-schema §4.2).
     */
    @Test
    void keysetPull_ordersBy_updatedAt_thenId() {
        User u = savedUser("supply-order@example.com");

        // Save A and B sequentially; B.updatedAt >= A.updatedAt after this
        SupplyItem a = buildItem(u.getId(), "Formula Tin", "feeding");
        a = items.saveAndFlush(a);

        SupplyItem b = buildItem(u.getId(), "Breast Pads", "hygiene");
        b = items.saveAndFlush(b);

        // Update A — now A.updatedAt is the latest
        a.setOnHandQty(10);
        a = items.saveAndFlush(a);
        Instant aUpdatedAt = a.getUpdatedAt();
        Instant bUpdatedAt = b.getUpdatedAt();

        // A was updated most recently → it must come after B in updatedAt ASC order
        assertThat(aUpdatedAt).isAfterOrEqualTo(bUpdatedAt);

        List<SupplyItem> found = items.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(found).hasSize(2);
        // B (earlier updatedAt) must come first; A (later updatedAt) must come second
        assertThat(found.get(found.size() - 1).getId()).isEqualTo(a.getId());
        // All returned rows belong to this user
        found.forEach(s -> assertThat(s.getUserId()).isEqualTo(u.getId()));
    }

    /**
     * The cursor-continuation query resumes correctly after the first batch.
     * After item A is updated, {@code findForPullAfterCursor} with B as the cursor position
     * returns only A (which has a later {@code updated_at}).
     */
    @Test
    void cursorContinuation_resumesAfterGivenPosition() {
        User u = savedUser("supply-cursor@example.com");

        SupplyItem a = buildItem(u.getId(), "Item A", "other");
        a = items.saveAndFlush(a);

        SupplyItem b = buildItem(u.getId(), "Item B", "other");
        b = items.saveAndFlush(b);

        // Update A so it has the latest updated_at
        a.setOnHandQty(5);
        a = items.saveAndFlush(a);

        // Cursor is positioned AT item B (earlier updated_at); we expect only A to follow
        UUID aId = a.getId();
        UUID bId = b.getId();
        List<SupplyItem> afterCursor = items.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                b.getUpdatedAt(), b.getId(),
                Pageable.ofSize(100));

        // A was updated after B's updated_at, so it must appear after the cursor
        assertThat(afterCursor).anyMatch(s -> s.getId().equals(aId));
        // B itself must NOT be returned (cursor is exclusive)
        assertThat(afterCursor).noneMatch(s -> s.getId().equals(bId));
    }

    /**
     * A soft-deleted item (tombstone) is still returned by the pull query
     * (so the deletion propagates to other devices) but is excluded from the live-list query.
     * This validates tombstone-propagation (sync spec §A.5 / database-schema §4.4).
     */
    @Test
    void tombstone_visibleInPullQuery_excludedFromLiveList() {
        User u = savedUser("supply-tombstone@example.com");

        SupplyItem item = buildItem(u.getId(), "Vitamin D", "health-supplies");
        items.saveAndFlush(item);

        // Soft-delete: set deleted_at (tombstone-wins)
        item.setDeletedAt(Instant.now());
        items.saveAndFlush(item);

        // Pull query includes tombstones (so other devices learn of the deletion)
        List<SupplyItem> pullResults = items.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(pullResults).hasSize(1);
        assertThat(pullResults.get(0).getDeletedAt()).isNotNull();

        // Live-list query excludes tombstones
        List<SupplyItem> liveResults = items.findByUserIdAndDeletedAtIsNull(u.getId(), Pageable.unpaged());
        assertThat(liveResults).isEmpty();
    }

    /**
     * Tombstone-only: {@code findByUserIdAndDeletedAtIsNull} excludes soft-deleted rows
     * while still allowing the tombstone to be read by other queries.
     */
    @Test
    void findByUserIdAndDeletedAtIsNull_excludesTombstones() {
        User u = savedUser("supply-live@example.com");

        SupplyItem live = buildItem(u.getId(), "Diapers", "diapers");
        SupplyItem dead = buildItem(u.getId(), "Old Cream", "hygiene");
        items.saveAndFlush(live);
        items.saveAndFlush(dead);

        dead.setDeletedAt(Instant.now());
        items.saveAndFlush(dead);

        List<SupplyItem> liveList = items.findByUserIdAndDeletedAtIsNull(u.getId(), Pageable.unpaged());
        assertThat(liveList).hasSize(1);
        assertThat(liveList.get(0).getName()).isEqualTo("Diapers");
    }

    /**
     * {@code @Version} (Long wrapper) starts at 0 on first persist and increments
     * on each update. This is the server-assigned monotonic optimistic-concurrency token
     * (sync spec §0.1 / data-model §2).
     */
    @Test
    void version_startsAtZero_incrementsOnUpdate() {
        User u = savedUser("supply-version@example.com");

        SupplyItem item = buildItem(u.getId(), "Nursing Pads", "feeding");
        item = items.saveAndFlush(item);

        assertThat(item.getVersion()).isEqualTo(0L);

        item.setOnHandQty(20);
        item = items.saveAndFlush(item);

        assertThat(item.getVersion()).isEqualTo(1L);
    }

    /**
     * The DB {@code CHECK (on_hand_qty >= 0)} constraint rejects a negative on-hand quantity.
     * The sync apply path must clamp to 0 BEFORE persisting (sync spec §A.7 / E10); this test
     * validates the DB backstop that catches any bypass.
     */
    @Test
    void onHandQty_negativeQuantity_rejectedByConstraint() {
        User u = savedUser("supply-qty@example.com");

        SupplyItem item = buildItem(u.getId(), "Formula", "feeding");
        item.setOnHandQty(-1);    // violates CHECK (on_hand_qty >= 0)

        assertThatThrownBy(() -> {
            items.save(item);
            items.flush();        // force SQL to fire the constraint
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@code low_threshold} must be non-negative when set (DB CHECK). A negative threshold
     * is rejected. A NULL threshold (item never raises alert) is accepted.
     */
    @Test
    void lowThreshold_negativeRejected_nullAllowed() {
        User u = savedUser("supply-threshold@example.com");

        // NULL is valid (item never raises a low-supply alert)
        SupplyItem noThreshold = buildItem(u.getId(), "Lanolin", "hygiene");
        noThreshold.setLowThreshold(null);
        SupplyItem saved = items.saveAndFlush(noThreshold);
        assertThat(saved.getLowThreshold()).isNull();

        // Negative threshold is rejected by CHECK (low_threshold >= 0)
        SupplyItem bad = buildItem(u.getId(), "Baby Oil", "hygiene");
        bad.setLowThreshold(-5);
        assertThatThrownBy(() -> {
            items.save(bad);
            items.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@link SupplyItemRepository#findByUserIdAndIdIn} returns only items whose ids appear
     * in the given collection and belong to the given user. Used by the sync/push apply path
     * to load existing rows for version comparison.
     */
    @Test
    void findByUserIdAndIdIn_returnsMatchingItems() {
        User u = savedUser("supply-batch@example.com");
        User other = savedUser("supply-other@example.com");

        SupplyItem a = buildItem(u.getId(), "Diapers", "diapers");
        SupplyItem b = buildItem(u.getId(), "Wipes", "hygiene");
        SupplyItem c = buildItem(other.getId(), "Formula", "feeding");  // different user
        items.saveAndFlush(a);
        items.saveAndFlush(b);
        items.saveAndFlush(c);

        List<UUID> ids = List.of(a.getId(), b.getId(), c.getId());
        List<SupplyItem> found = items.findByUserIdAndIdIn(u.getId(), ids);

        // Only a and b belong to u; c is a different user's item
        assertThat(found).hasSize(2);
        assertThat(found).extracting(SupplyItem::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    /**
     * The {@code low_notified_at_version} de-nag marker persists NULL (no alert) and
     * a non-null integer (alert fired) correctly. The server does NOT recompute this value
     * (sync spec §C.2 / data-model §3.9 — ordinary LWW field).
     */
    @Test
    void lowNotifiedAtVersion_nullAndNonNull_persisted() {
        User u = savedUser("supply-denag@example.com");

        SupplyItem item = buildItem(u.getId(), "Wipes", "diapers");
        item.setLowThreshold(2);
        item.setOnHandQty(1);       // below threshold: isLow = true (on-device logic)
        item = items.saveAndFlush(item);

        assertThat(item.getLowNotifiedAtVersion()).isNull();   // not yet alerted

        // Device fires the alert and sets the marker to the current version
        item.setLowNotifiedAtVersion(item.getVersion().intValue());
        item = items.saveAndFlush(item);

        assertThat(item.getLowNotifiedAtVersion()).isNotNull();

        // Device restocks above threshold → clears marker to NULL
        item.setOnHandQty(5);
        item.setLowNotifiedAtVersion(null);
        item = items.saveAndFlush(item);

        assertThat(item.getLowNotifiedAtVersion()).isNull();
    }

    /**
     * Pull query returns items scoped to the requesting user only — a different user's
     * items are never included in the response (data isolation invariant).
     */
    @Test
    void pullQuery_isolatesResultsByUserId() {
        User alice = savedUser("alice@example.com");
        User bob = savedUser("bob@example.com");

        items.saveAndFlush(buildItem(alice.getId(), "Alice Diapers", "diapers"));
        items.saveAndFlush(buildItem(bob.getId(), "Bob Formula", "feeding"));

        List<SupplyItem> aliceItems = items.findForPull(alice.getId(), Instant.EPOCH, Pageable.unpaged());
        List<SupplyItem> bobItems = items.findForPull(bob.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(aliceItems).hasSize(1);
        assertThat(aliceItems.get(0).getName()).isEqualTo("Alice Diapers");

        assertThat(bobItems).hasSize(1);
        assertThat(bobItems.get(0).getName()).isEqualTo("Bob Formula");
    }
}
