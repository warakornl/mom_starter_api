package com.momstarter.expense;

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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link Expense} / {@link ExpenseRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 in PostgreSQL mode,
 * {@code application-test.yml}). Mirrors the pattern in {@code SupplyItemRepositoryTest}.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Client-generated id is preserved on save (not overwritten by the server).</li>
 *   <li>Basic save and retrieval via the sync-pull keyset query.</li>
 *   <li>Keyset ordering: {@code (updated_at ASC, id ASC)} — the pull/cursor invariant.</li>
 *   <li>Soft-delete tombstone: included in pull, excluded from live-list.</li>
 *   <li>{@code @Version} starts at 0 on first persist, increments on update.</li>
 *   <li>DB {@code CHECK (amount >= 0)} rejects a negative amount.</li>
 *   <li>DB CHECK rejects an invalid category.</li>
 *   <li>{@link ExpenseRepository#findByUserIdAndIdIn} returns matching rows only (IDOR).</li>
 *   <li>Pull query isolates results by userId.</li>
 *   <li>{@link ExpenseRepository#findAllByUserIdForExport} returns all rows including tombstones.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class ExpenseRepositoryTest {

    @Autowired
    private ExpenseRepository expenses;

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
     * Builds a new (unsaved) Expense with a client-generated UUID already set.
     * The id is client-supplied — no server generation.
     */
    private Expense buildExpense(UUID userId, int amountSatang, String category) {
        Expense e = new Expense();
        e.setId(UUID.randomUUID());
        e.setUserId(userId);
        e.setAmount(amountSatang);
        e.setCategory(category);
        e.setIncurredOn(LocalDate.of(2026, 7, 1));
        return e;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * The client-generated UUID is preserved exactly on save.
     */
    @Test
    void clientSuppliedId_preserved_onSave() {
        User u = savedUser("expense-id@example.com");
        UUID clientId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        Expense e = buildExpense(u.getId(), 59000, "baby-supplies"); // ฿590
        e.setId(clientId);
        Expense saved = expenses.saveAndFlush(e);

        assertThat(saved.getId()).isEqualTo(clientId);
        assertThat(expenses.findById(clientId)).isPresent();
    }

    /**
     * A saved expense is returned by the sync-pull keyset query and has all mandatory
     * sync columns populated.
     */
    @Test
    void savesAndFinds_viaKeysetPullQuery() {
        User u = savedUser("expense-save@example.com");

        Expense e = buildExpense(u.getId(), 80000, "healthcare"); // ฿800
        e.setNote("ค่าตรวจ ANC");
        e.setClientId(UUID.randomUUID());
        expenses.saveAndFlush(e);

        List<Expense> found = expenses.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(found).hasSize(1);
        Expense f = found.get(0);
        assertThat(f.getAmount()).isEqualTo(80000);
        assertThat(f.getCategory()).isEqualTo("healthcare");
        assertThat(f.getNote()).isEqualTo("ค่าตรวจ ANC");
        assertThat(f.getIncurredOn()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(f.getClientId()).isNotNull();
        assertThat(f.getCreatedAt()).isNotNull();
        assertThat(f.getUpdatedAt()).isNotNull();
        assertThat(f.getDeletedAt()).isNull();
        assertThat(f.getVersion()).isNotNull();
    }

    /**
     * The pull keyset orders results by {@code (updated_at ASC, id ASC)}.
     */
    @Test
    void keysetPull_ordersBy_updatedAt_thenId() {
        User u = savedUser("expense-order@example.com");

        Expense a = buildExpense(u.getId(), 25000, "mother");
        a = expenses.saveAndFlush(a);

        Expense b = buildExpense(u.getId(), 58400, "baby-gear");
        b = expenses.saveAndFlush(b);

        // Update A — now A.updatedAt is the latest
        a.setAmount(30000);
        a = expenses.saveAndFlush(a);
        Instant aUpdatedAt = a.getUpdatedAt();
        Instant bUpdatedAt = b.getUpdatedAt();

        assertThat(aUpdatedAt).isAfterOrEqualTo(bUpdatedAt);

        List<Expense> found = expenses.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(found).hasSize(2);
        // A (later updatedAt) must come last
        assertThat(found.get(found.size() - 1).getId()).isEqualTo(a.getId());
        found.forEach(ex -> assertThat(ex.getUserId()).isEqualTo(u.getId()));
    }

    /**
     * Cursor-continuation query resumes correctly after the first batch.
     */
    @Test
    void cursorContinuation_resumesAfterGivenPosition() {
        User u = savedUser("expense-cursor@example.com");

        Expense a = buildExpense(u.getId(), 10000, "other");
        a = expenses.saveAndFlush(a);

        Expense b = buildExpense(u.getId(), 20000, "other");
        b = expenses.saveAndFlush(b);

        // Update A so it has the latest updated_at
        a.setAmount(15000);
        a = expenses.saveAndFlush(a);

        UUID aId = a.getId();
        UUID bId = b.getId();
        List<Expense> afterCursor = expenses.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                b.getUpdatedAt(), b.getId(),
                Pageable.ofSize(100));

        assertThat(afterCursor).anyMatch(ex -> ex.getId().equals(aId));
        assertThat(afterCursor).noneMatch(ex -> ex.getId().equals(bId));
    }

    /**
     * Tombstone is visible in pull (propagates deletion) but excluded from live-list.
     */
    @Test
    void tombstone_visibleInPullQuery_excludedFromLiveList() {
        User u = savedUser("expense-tombstone@example.com");

        Expense e = buildExpense(u.getId(), 42800, "baby-supplies");
        expenses.saveAndFlush(e);

        e.setDeletedAt(Instant.now());
        expenses.saveAndFlush(e);

        List<Expense> pullResults = expenses.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(pullResults).hasSize(1);
        assertThat(pullResults.get(0).getDeletedAt()).isNotNull();

        List<Expense> liveResults = expenses.findByUserIdAndDeletedAtIsNull(u.getId(), Pageable.unpaged());
        assertThat(liveResults).isEmpty();
    }

    /**
     * {@code findByUserIdAndDeletedAtIsNull} excludes soft-deleted rows.
     */
    @Test
    void findByUserIdAndDeletedAtIsNull_excludesTombstones() {
        User u = savedUser("expense-live@example.com");

        Expense live = buildExpense(u.getId(), 59000, "healthcare");
        Expense dead = buildExpense(u.getId(), 18400, "mother");
        expenses.saveAndFlush(live);
        expenses.saveAndFlush(dead);

        dead.setDeletedAt(Instant.now());
        expenses.saveAndFlush(dead);

        List<Expense> liveList = expenses.findByUserIdAndDeletedAtIsNull(u.getId(), Pageable.unpaged());
        assertThat(liveList).hasSize(1);
        assertThat(liveList.get(0).getAmount()).isEqualTo(59000);
    }

    /**
     * {@code @Version} starts at 0 on first persist and increments on each update.
     */
    @Test
    void version_startsAtZero_incrementsOnUpdate() {
        User u = savedUser("expense-version@example.com");

        Expense e = buildExpense(u.getId(), 10000, "other");
        e = expenses.saveAndFlush(e);

        assertThat(e.getVersion()).isEqualTo(0L);

        e.setAmount(12000);
        e = expenses.saveAndFlush(e);

        assertThat(e.getVersion()).isEqualTo(1L);
    }

    /**
     * DB CHECK (amount >= 0) rejects a negative amount.
     */
    @Test
    void amount_negativeAmount_rejectedByConstraint() {
        User u = savedUser("expense-amt@example.com");

        Expense e = buildExpense(u.getId(), -1, "other"); // negative = invalid

        assertThatThrownBy(() -> {
            expenses.save(e);
            expenses.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * DB CHECK rejects an invalid category value.
     */
    @Test
    void category_invalidValue_rejectedByConstraint() {
        User u = savedUser("expense-cat@example.com");

        Expense e = buildExpense(u.getId(), 10000, "invalid-category");

        assertThatThrownBy(() -> {
            expenses.save(e);
            expenses.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Note is nullable — an expense without a note is valid.
     */
    @Test
    void note_isNullable() {
        User u = savedUser("expense-note@example.com");

        Expense e = buildExpense(u.getId(), 25000, "baby-gear");
        e.setNote(null);
        Expense saved = expenses.saveAndFlush(e);

        assertThat(saved.getNote()).isNull();
    }

    /**
     * {@link ExpenseRepository#findByUserIdAndIdIn} returns only items belonging to the
     * given user and matching the given ids (IDOR guard for sync push apply path).
     */
    @Test
    void findByUserIdAndIdIn_returnsMatchingItems() {
        User u = savedUser("expense-batch@example.com");
        User other = savedUser("expense-other@example.com");

        Expense a = buildExpense(u.getId(), 59000, "healthcare");
        Expense b = buildExpense(u.getId(), 18400, "mother");
        Expense c = buildExpense(other.getId(), 10000, "other"); // different user
        expenses.saveAndFlush(a);
        expenses.saveAndFlush(b);
        expenses.saveAndFlush(c);

        List<Expense> found = expenses.findByUserIdAndIdIn(u.getId(),
                List.of(a.getId(), b.getId(), c.getId()));

        assertThat(found).hasSize(2);
        assertThat(found).extracting(Expense::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    /**
     * Pull query isolates results by userId — another user's expenses are never returned.
     */
    @Test
    void pullQuery_isolatesResultsByUserId() {
        User alice = savedUser("alice-expense@example.com");
        User bob = savedUser("bob-expense@example.com");

        expenses.saveAndFlush(buildExpense(alice.getId(), 42800, "baby-supplies"));
        expenses.saveAndFlush(buildExpense(bob.getId(), 80000, "healthcare"));

        List<Expense> aliceItems = expenses.findForPull(alice.getId(), Instant.EPOCH, Pageable.unpaged());
        List<Expense> bobItems = expenses.findForPull(bob.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(aliceItems).hasSize(1);
        assertThat(aliceItems.get(0).getAmount()).isEqualTo(42800);

        assertThat(bobItems).hasSize(1);
        assertThat(bobItems.get(0).getAmount()).isEqualTo(80000);
    }

    /**
     * {@link ExpenseRepository#findAllByUserIdForExport} returns ALL rows including tombstones.
     * Used by the PDPA ม.30/31 export path.
     */
    @Test
    void findAllByUserIdForExport_includesLiveAndTombstoned() {
        User u = savedUser("expense-export@example.com");

        Expense live = buildExpense(u.getId(), 59000, "healthcare");
        Expense tombstoned = buildExpense(u.getId(), 18400, "mother");
        expenses.saveAndFlush(live);
        expenses.saveAndFlush(tombstoned);

        tombstoned.setDeletedAt(Instant.now());
        expenses.saveAndFlush(tombstoned);

        List<Expense> exported = expenses.findAllByUserIdForExport(u.getId());
        assertThat(exported).hasSize(2);
        // Both live and tombstoned are present
        assertThat(exported).anyMatch(ex -> ex.getDeletedAt() == null);
        assertThat(exported).anyMatch(ex -> ex.getDeletedAt() != null);
    }
}
