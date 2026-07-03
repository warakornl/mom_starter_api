package com.momstarter.medication;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link MedicationPlan} / {@link MedicationPlanRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 in PostgreSQL mode,
 * {@code application-test.yml}). Mirrors the pattern in {@code ExpenseRepositoryTest} /
 * {@code SelfLogRepositoryTest}.
 *
 * <p>Pattern: <strong>MUTABLE-LWW</strong> (sibling of Expense / SupplyItem / ChecklistItem).
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Client-generated id preserved on save (no server generation).</li>
 *   <li>{@code scheduleRule} round-trip via {@code @JdbcTypeCode(SqlTypes.JSON)} mapping
 *       (jsonb column; H2 PostgreSQL-MODE accepts varchar→jsonb silently — annotation guards
 *       against the real-Postgres binding error — memory h2-masks-jsonb-binding).</li>
 *   <li>{@code @Version} starts at 0; {@link MedicationPlanRepository#initVersionToOne}
 *       bumps to 1; subsequent LWW update increments further.</li>
 *   <li>{@link MedicationPlanRepository#findByUserIdAndIdIn} IDOR guard.</li>
 *   <li>Sync-pull: tombstones included, user-isolated.</li>
 *   <li>Sync-pull cursor: {@link MedicationPlanRepository#findForPullAfterCursor} resumes.</li>
 *   <li>findForPullAfterCursor ASC tie-break: equal {@code updatedAt}, id-ASC keyset.</li>
 *   <li>Read-only REST: live rows only, {@code ORDER BY updated_at DESC, id DESC}, no from/to.</li>
 *   <li>findForReadAfterCursor DESC tie-break: equal {@code updatedAt}, id-DESC keyset.</li>
 *   <li>Soft-delete: excluded from {@code findForRead}, included in pull.</li>
 *   <li>{@code ck_medication_plan__live_name}: live plan with null name_cipher rejected.</li>
 *   <li>{@link MedicationPlanRepository#findAllByUserIdForExport}: all rows, including tombstones.</li>
 *   <li>{@code active} defaults to {@code true} when not explicitly set.</li>
 *   <li>{@code scheduleRule} is nullable (PRN / ad-hoc plan with no schedule).</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class MedicationPlanRepositoryTest {

    @Autowired
    private MedicationPlanRepository plans;

    @Autowired
    private UserRepository users;

    @Autowired
    private TestEntityManager testEntityManager;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private User savedUser(String email) {
        User u = new User();
        u.setEmail(email);
        return users.save(u);
    }

    /**
     * Builds an unsaved MedicationPlan with client-generated UUID, a live name_cipher,
     * and optional scheduleRule JSON string.
     */
    private MedicationPlan buildPlan(UUID userId) {
        MedicationPlan p = new MedicationPlan();
        p.setId(UUID.randomUUID());
        p.setUserId(userId);
        p.setNameCipher("Folic Acid".getBytes());
        p.setActive(true);
        return p;
    }

    private MedicationPlan buildPlanWithSchedule(UUID userId, String scheduleRuleJson) {
        MedicationPlan p = buildPlan(userId);
        p.setScheduleRule(scheduleRuleJson);
        return p;
    }

    // -------------------------------------------------------------------------
    // 1. Client-generated id preserved
    // -------------------------------------------------------------------------

    @Test
    void clientGeneratedId_preservedOnSave() {
        User u = savedUser("mp-repo-1@example.com");
        UUID clientId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        MedicationPlan p = buildPlan(u.getId());
        p.setId(clientId);
        plans.saveAndFlush(p);

        MedicationPlan loaded = plans.findById(clientId).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(clientId);
        assertThat(loaded.getUserId()).isEqualTo(u.getId());
    }

    // -------------------------------------------------------------------------
    // 2. scheduleRule jsonb round-trip (@JdbcTypeCode(SqlTypes.JSON) mapping)
    // -------------------------------------------------------------------------

    @Test
    void scheduleRule_jsonb_roundTrip() {
        // Pins the @JdbcTypeCode(SqlTypes.JSON) annotation behaviour.
        // H2 in PostgreSQL-MODE accepts varchar→jsonb silently (h2-masks-jsonb-binding memory).
        // Without @JdbcTypeCode, real Postgres would reject:
        //   ERROR: column "schedule_rule" is of type jsonb but expression is of type character varying
        // This test confirms the stored JSON is echoed back verbatim (round-trip).
        User u = savedUser("mp-repo-2@example.com");

        String scheduleJson = "{\"freq\":\"daily\",\"startAt\":\"2026-07-01T08:00\","
                + "\"timesOfDay\":[\"08:00\"],\"until\":\"2026-07-28\"}";

        MedicationPlan p = buildPlanWithSchedule(u.getId(), scheduleJson);
        plans.saveAndFlush(p);

        MedicationPlan loaded = plans.findById(p.getId()).orElseThrow();
        assertThat(loaded.getScheduleRule()).isEqualTo(scheduleJson);
    }

    @Test
    void scheduleRule_isNullable_prnPlan() {
        // null scheduleRule = PRN / ad-hoc plan (no recurring schedule, M=0 per spec §A.5).
        User u = savedUser("mp-repo-null-sr@example.com");

        MedicationPlan p = buildPlan(u.getId());
        p.setScheduleRule(null);
        MedicationPlan saved = plans.saveAndFlush(p);

        assertThat(saved.getScheduleRule()).isNull();
    }

    // -------------------------------------------------------------------------
    // 3. initVersionToOne — bumps version 0 → 1 (api-contract §5 pin)
    // -------------------------------------------------------------------------

    @Test
    void versionStartsAtZero_initVersionToOne_bumpsToOne() {
        User u = savedUser("mp-repo-3@example.com");
        MedicationPlan p = buildPlan(u.getId());

        p = plans.saveAndFlush(p);
        assertThat(p.getVersion()).isEqualTo(0L);

        plans.initVersionToOne(p.getId());
        plans.flush();

        MedicationPlan reloaded = plans.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void version_startsAtZero_incrementsOnUpdate() {
        // LWW mutable record: version increments on each update (Expense pattern).
        User u = savedUser("mp-repo-ver@example.com");

        MedicationPlan p = buildPlan(u.getId());
        p = plans.saveAndFlush(p);

        assertThat(p.getVersion()).isEqualTo(0L);

        p.setActive(false); // field edit
        p = plans.saveAndFlush(p);

        assertThat(p.getVersion()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // 4. findByUserIdAndIdIn — IDOR guard
    // -------------------------------------------------------------------------

    @Test
    void findByUserIdAndIdIn_returnsOnlyMatchingOwner() {
        User alice = savedUser("mp-alice@example.com");
        User bob   = savedUser("mp-bob@example.com");

        MedicationPlan alicePlan = buildPlan(alice.getId());
        MedicationPlan bobPlan   = buildPlan(bob.getId());
        plans.saveAll(List.of(alicePlan, bobPlan));

        List<MedicationPlan> found = plans.findByUserIdAndIdIn(
                alice.getId(), List.of(alicePlan.getId(), bobPlan.getId()));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(alicePlan.getId());
    }

    // -------------------------------------------------------------------------
    // 5. findForPull — includes tombstones (sync-pull propagates deletions)
    // -------------------------------------------------------------------------

    @Test
    void findForPull_includesTombstones() {
        User u = savedUser("mp-pull-tomb@example.com");

        MedicationPlan live       = buildPlan(u.getId());
        MedicationPlan tombstoned = buildPlan(u.getId());
        tombstoned.setDeletedAt(Instant.now());
        tombstoned.setNameCipher(null); // crypto-shredded name on tombstone
        // The CHECK (deleted_at IS NOT NULL OR name_cipher IS NOT NULL) allows this
        plans.saveAll(List.of(live, tombstoned));

        List<MedicationPlan> result = plans.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(result).hasSize(2);
    }

    @Test
    void findForPull_isolatesResultsByUserId() {
        User alice = savedUser("mp-pull-alice@example.com");
        User bob   = savedUser("mp-pull-bob@example.com");

        plans.saveAndFlush(buildPlan(alice.getId()));
        plans.saveAndFlush(buildPlan(bob.getId()));

        List<MedicationPlan> alicePlans = plans.findForPull(alice.getId(), Instant.EPOCH, Pageable.unpaged());
        List<MedicationPlan> bobPlans   = plans.findForPull(bob.getId(),   Instant.EPOCH, Pageable.unpaged());

        assertThat(alicePlans).hasSize(1);
        assertThat(bobPlans).hasSize(1);
        assertThat(alicePlans.get(0).getUserId()).isEqualTo(alice.getId());
        assertThat(bobPlans.get(0).getUserId()).isEqualTo(bob.getId());
    }

    // -------------------------------------------------------------------------
    // 6. findForPullAfterCursor — cursor continuation (ASC keyset)
    // -------------------------------------------------------------------------

    @Test
    void findForPullAfterCursor_resumesCorrectly() {
        User u = savedUser("mp-pull-cursor@example.com");

        MedicationPlan a = plans.saveAndFlush(buildPlan(u.getId()));
        MedicationPlan b = plans.saveAndFlush(buildPlan(u.getId()));

        // Using EPOCH cursor means both rows come after — basic sanity check
        List<MedicationPlan> afterEpochCursor = plans.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                Instant.EPOCH, UUID.fromString("00000000-0000-0000-0000-000000000000"),
                Pageable.ofSize(100));

        assertThat(afterEpochCursor).hasSize(2);

        // Use B as cursor: B must not appear in the result
        List<MedicationPlan> afterB = plans.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                b.getUpdatedAt(), b.getId(),
                Pageable.ofSize(100));

        assertThat(afterB).noneMatch(p -> p.getId().equals(b.getId()));
    }

    // -------------------------------------------------------------------------
    // 7. findForPullAfterCursor tie-break — equal updatedAt, id ASC keyset
    // -------------------------------------------------------------------------

    @Test
    void findForPullAfterCursor_tieBreak_sameUpdatedAt() {
        // Pins the tie-break branch: p.updatedAt = :cursorUpdatedAt AND p.id > :cursorId
        // (same scenario as SelfLogRepositoryTest#findForPullAfterCursor_tieBreak_sameUpdatedAt).
        //
        // Realistic: a batch push stamps multiple plans with the same server-assigned updated_at.
        // Full ASC order at the shared instant is (lower-id first), so lower-id is the cursor.
        // The next page must begin with the same-updatedAt higher-id row via the tie-break.
        User u = savedUser("mp-tiebreak-pull@example.com");

        UUID tiedLowId  = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID tiedHighId = UUID.fromString("20000000-0000-0000-0000-000000000002");

        MedicationPlan beforeTied = buildPlan(u.getId());
        MedicationPlan tiedLow    = buildPlan(u.getId());
        MedicationPlan tiedHigh   = buildPlan(u.getId());
        MedicationPlan afterTied  = buildPlan(u.getId());

        tiedLow.setId(tiedLowId);
        tiedHigh.setId(tiedHighId);

        plans.saveAll(List.of(beforeTied, tiedLow, tiedHigh, afterTied));
        plans.flush();

        Instant tShared = Instant.parse("2020-01-01T12:00:00Z");
        Instant tEarly  = Instant.parse("2020-01-01T10:00:00Z");
        Instant tLate   = Instant.parse("2020-01-01T14:00:00Z");

        jakarta.persistence.EntityManager em = testEntityManager.getEntityManager();
        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tShared)).setParameter(2, tiedLowId).executeUpdate();
        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tShared)).setParameter(2, tiedHighId).executeUpdate();
        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tEarly)).setParameter(2, beforeTied.getId()).executeUpdate();
        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tLate)).setParameter(2, afterTied.getId()).executeUpdate();
        em.clear();

        // Full ASC order: [beforeTied(tEarly), tiedLow(tShared), tiedHigh(tShared), afterTied(tLate)].
        // Cursor at tiedLow (updatedAt=tShared, id=tiedLowId).
        // Expected page 2: tiedHigh first (tie-break), then afterTied.
        List<MedicationPlan> page2 = plans.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                tShared, tiedLowId,
                Pageable.ofSize(100));

        assertThat(page2).hasSize(2);
        assertThat(page2.get(0).getId()).isEqualTo(tiedHighId);
        assertThat(page2.get(1).getId()).isEqualTo(afterTied.getId());

        assertThat(page2).noneMatch(p -> p.getId().equals(tiedLowId));
        assertThat(page2).noneMatch(p -> p.getId().equals(beforeTied.getId()));
    }

    // -------------------------------------------------------------------------
    // 8. findForRead — live rows only, ORDER BY updatedAt DESC, id DESC (no from/to)
    // -------------------------------------------------------------------------

    @Test
    void findForRead_excludesSoftDeleted() {
        User u = savedUser("mp-read-del@example.com");

        MedicationPlan live = buildPlan(u.getId());
        MedicationPlan dead = buildPlan(u.getId());
        plans.saveAndFlush(live);
        plans.saveAndFlush(dead);

        dead.setDeletedAt(Instant.now());
        dead.setNameCipher(null); // crypto-shredded on tombstone
        plans.saveAndFlush(dead);

        List<MedicationPlan> result = plans.findForRead(u.getId(), Pageable.ofSize(100));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(live.getId());
    }

    @Test
    void findForRead_orderedByUpdatedAtDescThenIdDesc() {
        // GET /medication-plans orders by updated_at DESC, id DESC (spec §A.2).
        User u = savedUser("mp-read-order@example.com");

        MedicationPlan first  = plans.saveAndFlush(buildPlan(u.getId()));
        MedicationPlan second = plans.saveAndFlush(buildPlan(u.getId()));

        // Update first so it has a later updatedAt — should come first in DESC order
        first.setActive(false);
        first = plans.saveAndFlush(first);

        List<MedicationPlan> result = plans.findForRead(u.getId(), Pageable.ofSize(100));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(first.getId()); // later updatedAt — DESC first
        assertThat(result.get(1).getId()).isEqualTo(second.getId());
    }

    // -------------------------------------------------------------------------
    // 9. findForReadAfterCursor — DESC keyset for GET /medication-plans pagination
    // -------------------------------------------------------------------------

    @Test
    void findForReadAfterCursor_resumesAfterPosition() {
        User u = savedUser("mp-readcursor@example.com");

        // Three plans, force updatedAt order by sequential saves and updates
        MedicationPlan oldest = buildPlan(u.getId());
        MedicationPlan middle = buildPlan(u.getId());
        MedicationPlan newest = buildPlan(u.getId());
        plans.saveAll(List.of(oldest, middle, newest));

        // Touch newest last so its updatedAt is latest
        newest.setActive(true);
        MedicationPlan savedNewest = plans.saveAndFlush(newest);

        List<MedicationPlan> afterNewest = plans.findForReadAfterCursor(
                u.getId(), savedNewest.getUpdatedAt(), savedNewest.getId(),
                Pageable.ofSize(100));

        UUID newestId = savedNewest.getId();
        assertThat(afterNewest).noneMatch(p -> p.getId().equals(newestId));
    }

    @Test
    void findForReadAfterCursor_excludesSoftDeleted() {
        User u = savedUser("mp-readcursor-del@example.com");

        MedicationPlan cursor = buildPlan(u.getId());
        MedicationPlan live   = buildPlan(u.getId());
        MedicationPlan dead   = buildPlan(u.getId());
        plans.saveAll(List.of(cursor, live, dead));

        dead.setDeletedAt(Instant.now());
        dead.setNameCipher(null);
        plans.saveAndFlush(dead);

        // Touch cursor so its updatedAt > live and dead → cursor is "newest" in DESC
        cursor.setActive(true);
        cursor = plans.saveAndFlush(cursor);

        List<MedicationPlan> result = plans.findForReadAfterCursor(
                u.getId(), cursor.getUpdatedAt(), cursor.getId(),
                Pageable.ofSize(100));

        // dead must be excluded from read results
        assertThat(result).noneMatch(p -> p.getId().equals(dead.getId()));
    }

    // -------------------------------------------------------------------------
    // 10. findForReadAfterCursor tie-break — equal updatedAt, id DESC keyset
    // -------------------------------------------------------------------------

    @Test
    void findForReadAfterCursor_tieBreak_sameUpdatedAt() {
        // Pins the tie-break branch: p.updatedAt = :cursorUpdatedAt AND p.id < :cursorId
        //
        // DESC order at a shared updatedAt: higher-id row comes first (page 1 tail).
        // Cursor at higher-id → next page starts with lower-id row via tie-break.
        User u = savedUser("mp-tiebreak-read@example.com");

        UUID tiedLowId  = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID tiedHighId = UUID.fromString("20000000-0000-0000-0000-000000000002");

        MedicationPlan newer    = buildPlan(u.getId());
        MedicationPlan tiedHigh = buildPlan(u.getId());
        MedicationPlan tiedLow  = buildPlan(u.getId());
        MedicationPlan older    = buildPlan(u.getId());

        tiedHigh.setId(tiedHighId);
        tiedLow.setId(tiedLowId);

        plans.saveAll(List.of(newer, tiedHigh, tiedLow, older));
        plans.flush();

        Instant tShared = Instant.parse("2020-06-01T12:00:00Z");
        Instant tNewer  = Instant.parse("2020-06-01T14:00:00Z");
        Instant tOlder  = Instant.parse("2020-06-01T10:00:00Z");

        jakarta.persistence.EntityManager em = testEntityManager.getEntityManager();
        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tShared)).setParameter(2, tiedLowId).executeUpdate();
        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tShared)).setParameter(2, tiedHighId).executeUpdate();
        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tNewer)).setParameter(2, newer.getId()).executeUpdate();
        em.createNativeQuery("UPDATE medication_plan SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tOlder)).setParameter(2, older.getId()).executeUpdate();
        em.clear();

        // Full DESC order: [newer(tNewer), tiedHigh(tShared), tiedLow(tShared), older(tOlder)].
        // Cursor at tiedHigh (higher-id at shared updatedAt — tail of page 1).
        // Expected page 2: tiedLow first (tie-break), then older.
        List<MedicationPlan> page2 = plans.findForReadAfterCursor(
                u.getId(), tShared, tiedHighId,
                Pageable.ofSize(100));

        assertThat(page2).hasSize(2);
        assertThat(page2.get(0).getId()).isEqualTo(tiedLowId);
        assertThat(page2.get(1).getId()).isEqualTo(older.getId());

        assertThat(page2).noneMatch(p -> p.getId().equals(newer.getId()));
        assertThat(page2).noneMatch(p -> p.getId().equals(tiedHighId));
    }

    // -------------------------------------------------------------------------
    // 11. ck_medication_plan__live_name — live plan with null name_cipher rejected
    // -------------------------------------------------------------------------

    @Test
    void livePlanWithNullNameCipher_rejectedByCheckConstraint() {
        // The CHECK (deleted_at IS NOT NULL OR name_cipher IS NOT NULL) guards live rows.
        // A live plan (deleted_at IS NULL) with name_cipher = NULL violates the constraint.
        User u = savedUser("mp-check@example.com");

        MedicationPlan p = new MedicationPlan();
        p.setId(UUID.randomUUID());
        p.setUserId(u.getId());
        p.setNameCipher(null);  // live + null name = CHECK violation
        p.setActive(true);

        assertThatThrownBy(() -> {
            plans.save(p);
            plans.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // -------------------------------------------------------------------------
    // 12. findAllByUserIdForExport — all rows including tombstones (PDPA ม.30/31)
    // -------------------------------------------------------------------------

    @Test
    void findAllByUserIdForExport_includesLiveAndTombstoned() {
        User u = savedUser("mp-export@example.com");

        MedicationPlan live = buildPlan(u.getId());
        MedicationPlan dead = buildPlan(u.getId());
        plans.saveAll(List.of(live, dead));

        dead.setDeletedAt(Instant.now());
        dead.setNameCipher(null);
        plans.saveAndFlush(dead);

        List<MedicationPlan> exported = plans.findAllByUserIdForExport(u.getId());
        assertThat(exported).hasSize(2);
        assertThat(exported).anyMatch(p -> p.getDeletedAt() == null);
        assertThat(exported).anyMatch(p -> p.getDeletedAt() != null);
    }

    // -------------------------------------------------------------------------
    // 13. active defaults to true; doseCipher, sourceSuggestionStateId nullable
    // -------------------------------------------------------------------------

    @Test
    void active_defaultsToTrue_andOptionalFieldsAreNullable() {
        User u = savedUser("mp-defaults@example.com");

        MedicationPlan p = new MedicationPlan();
        p.setId(UUID.randomUUID());
        p.setUserId(u.getId());
        p.setNameCipher("Aspirin".getBytes());
        // active, doseCipher, scheduleRule, sourceSuggestionStateId, clientId all unset

        MedicationPlan saved = plans.saveAndFlush(p);

        assertThat(saved.isActive()).isTrue(); // DB DEFAULT true
        assertThat(saved.getDoseCipher()).isNull();
        assertThat(saved.getScheduleRule()).isNull();
        assertThat(saved.getSourceSuggestionStateId()).isNull();
        assertThat(saved.getClientId()).isNull();
    }

    @Test
    void sourceSuggestionStateId_softRef_storedVerbatim() {
        // source_suggestion_state_id is a SOFT LINK (no FK) — stored as opaque uuid.
        User u = savedUser("mp-sugref@example.com");

        UUID suggestionId = UUID.randomUUID();
        MedicationPlan p = buildPlan(u.getId());
        p.setSourceSuggestionStateId(suggestionId);

        MedicationPlan saved = plans.saveAndFlush(p);

        assertThat(saved.getSourceSuggestionStateId()).isEqualTo(suggestionId);
    }

    // -------------------------------------------------------------------------
    // 14. Sync block — createdAt, updatedAt, clientId, deletedAt
    // -------------------------------------------------------------------------

    @Test
    void syncBlock_timestamps_andClientId_storedCorrectly() {
        User u = savedUser("mp-sync@example.com");

        MedicationPlan p = buildPlan(u.getId());
        UUID deviceId = UUID.randomUUID();
        p.setClientId(deviceId);

        MedicationPlan saved = plans.saveAndFlush(p);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getClientId()).isEqualTo(deviceId);
        assertThat(saved.getVersion()).isNotNull();
    }
}
