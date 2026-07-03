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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link MedicationLog} / {@link MedicationLogRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 in PostgreSQL mode,
 * {@code application-test.yml}). Mirrors the pattern in {@code SelfLogRepositoryTest}.
 *
 * <p>Pattern: <strong>IMMUTABLE EVENT</strong> (sibling of SelfLog / KickCountSession).
 * Create-only union-merge; re-push of same id is an idempotent no-op; the only post-create
 * mutation is the soft-delete tombstone.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Client-generated id preserved on save (no server generation).</li>
 *   <li>{@code noteCipher} (bytea) stored and echoed verbatim (INV-M3).</li>
 *   <li>{@code loggedAt} server-assigned on INSERT (not in MedicationLogInput).</li>
 *   <li>{@code @Version} starts at 0; {@link MedicationLogRepository#initVersionToOne} bumps to 1.</li>
 *   <li>{@link MedicationLogRepository#findByUserIdAndIdIn} IDOR guard.</li>
 *   <li>Sync-pull: tombstones included.</li>
 *   <li>Sync-pull cursor: {@link MedicationLogRepository#findForPullAfterCursor} resumes.</li>
 *   <li>findForPullAfterCursor ASC tie-break: equal {@code updatedAt}, id-ASC keyset.</li>
 *   <li>Read-only REST: live rows only, keyed on {@code occurrenceTime} with optional
 *       {@code from}/{@code to} range; {@code ORDER BY occurrenceTime DESC, id DESC}.</li>
 *   <li>findForRead: plan-agnostic (no medicationPlanId filter).</li>
 *   <li>findForReadAfterCursor DESC tie-break: equal {@code occurrenceTime}, id-DESC keyset
 *       (required by spec reviewer — "include it now").</li>
 *   <li>Soft-delete: excluded from {@code findForRead}, included in pull.</li>
 *   <li>DB CHECK rejects invalid {@code status} (only 'taken'|'missed' allowed).</li>
 *   <li>Ad-hoc log ({@code medicationPlanId = null}) is legal.</li>
 *   <li>Linked log ({@code medicationPlanId} set) stores and loads correctly.</li>
 *   <li>{@link MedicationLogRepository#findAllByUserIdForExport}: all rows including tombstones.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class MedicationLogRepositoryTest {

    @Autowired
    private MedicationLogRepository logs;

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

    /** Builds an unsaved MedicationLog with client-generated UUID. */
    private MedicationLog buildLog(UUID userId, String status, LocalDateTime occurrenceTime) {
        MedicationLog l = new MedicationLog();
        l.setId(UUID.randomUUID());
        l.setUserId(userId);
        l.setStatus(status);
        l.setOccurrenceTime(occurrenceTime);
        return l;
    }

    private MedicationLog buildLog(UUID userId) {
        return buildLog(userId, "taken", LocalDateTime.of(2026, 7, 1, 8, 0));
    }

    /** Creates and persists a minimal MedicationPlan for FK tests. */
    private MedicationPlan savedPlan(UUID userId) {
        MedicationPlan p = new MedicationPlan();
        p.setId(UUID.randomUUID());
        p.setUserId(userId);
        p.setNameCipher("Iron supplement".getBytes());
        p.setActive(true);
        return plans.saveAndFlush(p);
    }

    // -------------------------------------------------------------------------
    // 1. Client-generated id preserved
    // -------------------------------------------------------------------------

    @Test
    void clientGeneratedId_preservedOnSave() {
        User u = savedUser("ml-repo-1@example.com");
        UUID clientId = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

        MedicationLog l = buildLog(u.getId());
        l.setId(clientId);
        logs.saveAndFlush(l);

        MedicationLog loaded = logs.findById(clientId).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(clientId);
        assertThat(loaded.getUserId()).isEqualTo(u.getId());
    }

    // -------------------------------------------------------------------------
    // 2. noteCipher — bytea stored and echoed verbatim (INV-M3)
    // -------------------------------------------------------------------------

    @Test
    void noteCipher_storedAndEchoedVerbatim() {
        User u = savedUser("ml-repo-2@example.com");
        MedicationLog l = buildLog(u.getId(), "taken", LocalDateTime.of(2026, 7, 2, 9, 0));

        byte[] cipherBytes = new byte[]{0x01, 0x02, 0x03, 0x04};
        l.setNoteCipher(cipherBytes);

        logs.saveAndFlush(l);

        MedicationLog loaded = logs.findById(l.getId()).orElseThrow();
        assertThat(loaded.getNoteCipher()).isEqualTo(cipherBytes);
    }

    @Test
    void noteCipher_isNullable() {
        User u = savedUser("ml-repo-note-null@example.com");
        MedicationLog l = buildLog(u.getId());
        l.setNoteCipher(null);

        MedicationLog saved = logs.saveAndFlush(l);
        assertThat(saved.getNoteCipher()).isNull();
    }

    // -------------------------------------------------------------------------
    // 3. loggedAt — server-assigned on INSERT, never in MedicationLogInput
    // -------------------------------------------------------------------------

    @Test
    void loggedAt_serverAssignedOnInsert() {
        // loggedAt is NOT provided by the client (D5 / spec §1.2). The @PrePersist
        // lifecycle assigns it on first save alongside createdAt.
        User u = savedUser("ml-repo-logged@example.com");
        MedicationLog l = buildLog(u.getId());

        MedicationLog saved = logs.saveAndFlush(l);

        assertThat(saved.getLoggedAt()).isNotNull();
        // loggedAt is an absolute-UTC Instant (timestamptz), distinct from occurrenceTime
        assertThat(saved.getOccurrenceTime()).isEqualTo(LocalDateTime.of(2026, 7, 1, 8, 0));
    }

    // -------------------------------------------------------------------------
    // 4. initVersionToOne — bumps version 0 → 1 (api-contract §5)
    // -------------------------------------------------------------------------

    @Test
    void versionStartsAtZero_initVersionToOne_bumpsToOne() {
        User u = savedUser("ml-repo-3@example.com");
        MedicationLog l = buildLog(u.getId());

        l = logs.saveAndFlush(l);
        assertThat(l.getVersion()).isEqualTo(0L);

        logs.initVersionToOne(l.getId());
        logs.flush();

        MedicationLog reloaded = logs.findById(l.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // 5. findByUserIdAndIdIn — IDOR guard
    // -------------------------------------------------------------------------

    @Test
    void findByUserIdAndIdIn_returnsOnlyMatchingOwner() {
        User alice = savedUser("ml-alice@example.com");
        User bob   = savedUser("ml-bob@example.com");

        MedicationLog aliceLog = buildLog(alice.getId());
        MedicationLog bobLog   = buildLog(bob.getId());
        logs.saveAll(List.of(aliceLog, bobLog));

        List<MedicationLog> found = logs.findByUserIdAndIdIn(
                alice.getId(), List.of(aliceLog.getId(), bobLog.getId()));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(aliceLog.getId());
    }

    // -------------------------------------------------------------------------
    // 6. findForPull — includes tombstones (sync-pull propagates deletions)
    // -------------------------------------------------------------------------

    @Test
    void findForPull_includesTombstones() {
        User u = savedUser("ml-pull-tomb@example.com");

        MedicationLog live       = buildLog(u.getId());
        MedicationLog tombstoned = buildLog(u.getId(), "missed", LocalDateTime.of(2026, 7, 2, 9, 0));
        tombstoned.setDeletedAt(Instant.now());
        tombstoned.setNoteCipher(null); // crypto-shredded on tombstone
        logs.saveAll(List.of(live, tombstoned));

        List<MedicationLog> result = logs.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(result).hasSize(2);
    }

    @Test
    void findForPull_isolatesResultsByUserId() {
        User alice = savedUser("ml-pull-alice@example.com");
        User bob   = savedUser("ml-pull-bob@example.com");

        logs.saveAndFlush(buildLog(alice.getId()));
        logs.saveAndFlush(buildLog(bob.getId()));

        List<MedicationLog> aliceLogs = logs.findForPull(alice.getId(), Instant.EPOCH, Pageable.unpaged());
        List<MedicationLog> bobLogs   = logs.findForPull(bob.getId(),   Instant.EPOCH, Pageable.unpaged());

        assertThat(aliceLogs).hasSize(1);
        assertThat(bobLogs).hasSize(1);
        assertThat(aliceLogs.get(0).getUserId()).isEqualTo(alice.getId());
        assertThat(bobLogs.get(0).getUserId()).isEqualTo(bob.getId());
    }

    // -------------------------------------------------------------------------
    // 7. findForPullAfterCursor — cursor continuation (ASC keyset)
    // -------------------------------------------------------------------------

    @Test
    void findForPullAfterCursor_resumesCorrectly() {
        User u = savedUser("ml-pull-cursor@example.com");

        MedicationLog a = logs.saveAndFlush(buildLog(u.getId()));
        MedicationLog b = logs.saveAndFlush(buildLog(u.getId()));

        List<MedicationLog> afterEpochCursor = logs.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                Instant.EPOCH, UUID.fromString("00000000-0000-0000-0000-000000000000"),
                Pageable.ofSize(100));

        assertThat(afterEpochCursor).hasSize(2);

        List<MedicationLog> afterB = logs.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                b.getUpdatedAt(), b.getId(),
                Pageable.ofSize(100));

        assertThat(afterB).noneMatch(l -> l.getId().equals(b.getId()));
    }

    // -------------------------------------------------------------------------
    // 8. findForPullAfterCursor tie-break — equal updatedAt, id ASC keyset
    // -------------------------------------------------------------------------

    @Test
    void findForPullAfterCursor_tieBreak_sameUpdatedAt() {
        // Pins the tie-break branch: l.updatedAt = :cursorUpdatedAt AND l.id > :cursorId
        // (mirrors SelfLogRepositoryTest#findForPullAfterCursor_tieBreak_sameUpdatedAt).
        User u = savedUser("ml-tiebreak-pull@example.com");

        UUID tiedLowId  = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID tiedHighId = UUID.fromString("20000000-0000-0000-0000-000000000002");

        MedicationLog beforeTied = buildLog(u.getId());
        MedicationLog tiedLow    = buildLog(u.getId());
        MedicationLog tiedHigh   = buildLog(u.getId());
        MedicationLog afterTied  = buildLog(u.getId());

        tiedLow.setId(tiedLowId);
        tiedHigh.setId(tiedHighId);

        logs.saveAll(List.of(beforeTied, tiedLow, tiedHigh, afterTied));
        logs.flush();

        Instant tShared = Instant.parse("2020-01-01T12:00:00Z");
        Instant tEarly  = Instant.parse("2020-01-01T10:00:00Z");
        Instant tLate   = Instant.parse("2020-01-01T14:00:00Z");

        jakarta.persistence.EntityManager em = testEntityManager.getEntityManager();
        em.createNativeQuery("UPDATE medication_log SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tShared)).setParameter(2, tiedLowId).executeUpdate();
        em.createNativeQuery("UPDATE medication_log SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tShared)).setParameter(2, tiedHighId).executeUpdate();
        em.createNativeQuery("UPDATE medication_log SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tEarly)).setParameter(2, beforeTied.getId()).executeUpdate();
        em.createNativeQuery("UPDATE medication_log SET updated_at = ? WHERE id = ?")
                .setParameter(1, Timestamp.from(tLate)).setParameter(2, afterTied.getId()).executeUpdate();
        em.clear();

        // Full ASC order: [beforeTied(tEarly), tiedLow(tShared), tiedHigh(tShared), afterTied(tLate)].
        // Cursor at tiedLow. Expected page 2: tiedHigh first (tie-break), then afterTied.
        List<MedicationLog> page2 = logs.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                tShared, tiedLowId,
                Pageable.ofSize(100));

        assertThat(page2).hasSize(2);
        assertThat(page2.get(0).getId()).isEqualTo(tiedHighId);
        assertThat(page2.get(1).getId()).isEqualTo(afterTied.getId());

        assertThat(page2).noneMatch(l -> l.getId().equals(tiedLowId));
        assertThat(page2).noneMatch(l -> l.getId().equals(beforeTied.getId()));
    }

    // -------------------------------------------------------------------------
    // 9. findForRead — live rows only, occurrenceTime filter, ORDER BY occurrenceTime DESC, id DESC
    // -------------------------------------------------------------------------

    @Test
    void findForRead_excludesSoftDeleted() {
        User u = savedUser("ml-read-del@example.com");

        MedicationLog live = buildLog(u.getId(), "taken",  LocalDateTime.of(2026, 7, 1, 9, 0));
        MedicationLog dead = buildLog(u.getId(), "missed", LocalDateTime.of(2026, 7, 2, 9, 0));
        logs.saveAll(List.of(live, dead));

        dead.setDeletedAt(Instant.now());
        dead.setNoteCipher(null);
        logs.saveAndFlush(dead);

        List<MedicationLog> result = logs.findForRead(u.getId(), null, null, Pageable.ofSize(100));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(live.getId());
    }

    @Test
    void findForRead_dateRangeFilter_fromAndTo() {
        // from/to filter on occurrence_time (floating-civil, FLAG-1).
        User u = savedUser("ml-read-range@example.com");

        MedicationLog early = buildLog(u.getId(), "taken",  LocalDateTime.of(2026, 6, 1, 9, 0));
        MedicationLog mid   = buildLog(u.getId(), "taken",  LocalDateTime.of(2026, 7, 1, 9, 0));
        MedicationLog late  = buildLog(u.getId(), "missed", LocalDateTime.of(2026, 8, 1, 9, 0));
        logs.saveAll(List.of(early, mid, late));

        LocalDateTime from = LocalDateTime.of(2026, 6, 15, 0, 0);
        LocalDateTime to   = LocalDateTime.of(2026, 7, 31, 23, 59);

        List<MedicationLog> result = logs.findForRead(u.getId(), from, to, Pageable.ofSize(100));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(mid.getId());
    }

    @Test
    void findForRead_nullFromTo_returnsAllLiveRows() {
        // null from/to = no range filter (plan-agnostic, all statuses).
        User u = savedUser("ml-read-no-range@example.com");

        logs.saveAndFlush(buildLog(u.getId(), "taken",  LocalDateTime.of(2026, 7, 1, 8, 0)));
        logs.saveAndFlush(buildLog(u.getId(), "missed", LocalDateTime.of(2026, 7, 2, 9, 0)));
        logs.saveAndFlush(buildLog(u.getId(), "taken",  LocalDateTime.of(2026, 7, 3, 10, 0)));

        List<MedicationLog> result = logs.findForRead(u.getId(), null, null, Pageable.ofSize(100));

        assertThat(result).hasSize(3);
    }

    @Test
    void findForRead_orderedByOccurrenceTimeDescThenIdDesc() {
        // GET /medication-logs orders by occurrence_time DESC, id DESC (spec §A.3).
        User u = savedUser("ml-read-order@example.com");

        MedicationLog oldest = buildLog(u.getId(), "taken", LocalDateTime.of(2026, 7,  1, 8, 0));
        MedicationLog middle = buildLog(u.getId(), "taken", LocalDateTime.of(2026, 7,  5, 8, 0));
        MedicationLog newest = buildLog(u.getId(), "taken", LocalDateTime.of(2026, 7, 10, 8, 0));
        logs.saveAll(List.of(oldest, middle, newest));

        List<MedicationLog> result = logs.findForRead(u.getId(), null, null, Pageable.ofSize(100));

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getId()).isEqualTo(newest.getId()); // latest first
        assertThat(result.get(1).getId()).isEqualTo(middle.getId());
        assertThat(result.get(2).getId()).isEqualTo(oldest.getId());
    }

    @Test
    void findForRead_planAgnostic_returnsBothLinkedAndAdHoc() {
        // findForRead has no medicationPlanId filter — returns all logs for user (plan-agnostic).
        User u = savedUser("ml-read-agnostic@example.com");
        MedicationPlan plan = savedPlan(u.getId());

        MedicationLog linked = buildLog(u.getId(), "taken",  LocalDateTime.of(2026, 7, 1, 8, 0));
        linked.setMedicationPlanId(plan.getId()); // linked to a plan

        MedicationLog adHoc = buildLog(u.getId(), "missed", LocalDateTime.of(2026, 7, 2, 9, 0));
        // adHoc.medicationPlanId = null (no plan)

        logs.saveAll(List.of(linked, adHoc));

        List<MedicationLog> result = logs.findForRead(u.getId(), null, null, Pageable.ofSize(100));

        assertThat(result).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // 10. findForReadAfterCursor — DESC keyset for GET /medication-logs pagination
    // -------------------------------------------------------------------------

    @Test
    void findForReadAfterCursor_resumesAfterPosition() {
        User u = savedUser("ml-readcursor@example.com");

        MedicationLog newest = buildLog(u.getId(), "taken", LocalDateTime.of(2026, 7, 10, 9, 0));
        MedicationLog middle = buildLog(u.getId(), "taken", LocalDateTime.of(2026, 7,  5, 9, 0));
        MedicationLog oldest = buildLog(u.getId(), "taken", LocalDateTime.of(2026, 7,  1, 9, 0));
        logs.saveAll(List.of(newest, middle, oldest));

        // Cursor at "newest" → returns middle + oldest
        List<MedicationLog> afterNewest = logs.findForReadAfterCursor(
                u.getId(), null, null,
                newest.getOccurrenceTime(), newest.getId(),
                Pageable.ofSize(100));

        assertThat(afterNewest).hasSize(2);
        assertThat(afterNewest).noneMatch(l -> l.getId().equals(newest.getId()));
        assertThat(afterNewest).anyMatch(l -> l.getId().equals(middle.getId()));
        assertThat(afterNewest).anyMatch(l -> l.getId().equals(oldest.getId()));
    }

    @Test
    void findForReadAfterCursor_excludesSoftDeleted() {
        User u = savedUser("ml-readcursor-del@example.com");

        MedicationLog cursor = buildLog(u.getId(), "taken",  LocalDateTime.of(2026, 7, 15, 9, 0));
        MedicationLog live   = buildLog(u.getId(), "taken",  LocalDateTime.of(2026, 7, 10, 9, 0));
        MedicationLog dead   = buildLog(u.getId(), "missed", LocalDateTime.of(2026, 7,  1, 9, 0));
        logs.saveAll(List.of(cursor, live, dead));

        dead.setDeletedAt(Instant.now());
        dead.setNoteCipher(null);
        logs.saveAndFlush(dead);

        List<MedicationLog> result = logs.findForReadAfterCursor(
                u.getId(), null, null,
                cursor.getOccurrenceTime(), cursor.getId(),
                Pageable.ofSize(100));

        // dead must not appear even though its occurrenceTime < cursor (it's soft-deleted)
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(live.getId());
    }

    // -------------------------------------------------------------------------
    // 11. findForReadAfterCursor tie-break — equal occurrenceTime, id DESC keyset
    //     (self-log reviewer required this: "include it now")
    // -------------------------------------------------------------------------

    @Test
    void findForReadAfterCursor_tieBreak_sameOccurrenceTime() {
        // Pins the tie-break branch: l.occurrenceTime = :cursorOccurrenceTime AND l.id < :cursorId
        //
        // Realistic scenario: two dose logs recorded at the same civil minute (same occurrence).
        // Full DESC order at the shared occurrenceTime is (higher-id first), so higher-id lands
        // on page 1 as the cursor. Setting cursor at higher-id must yield lower-id as FIRST
        // item on page 2 via the tie-break, with no row skipped or duplicated.
        User u = savedUser("ml-tiebreak-read@example.com");

        UUID tiedLowId  = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID tiedHighId = UUID.fromString("20000000-0000-0000-0000-000000000002");

        LocalDateTime sharedOccurrence = LocalDateTime.of(2026, 7, 5, 9, 0);

        MedicationLog newer    = buildLog(u.getId(), "taken", LocalDateTime.of(2026, 7, 10, 9, 0));
        MedicationLog tiedHigh = buildLog(u.getId(), "taken", sharedOccurrence);
        MedicationLog tiedLow  = buildLog(u.getId(), "taken", sharedOccurrence);
        MedicationLog older    = buildLog(u.getId(), "taken", LocalDateTime.of(2026, 7,  1, 9, 0));

        tiedHigh.setId(tiedHighId);
        tiedLow.setId(tiedLowId);

        logs.saveAll(List.of(newer, tiedHigh, tiedLow, older));
        logs.flush();

        // Full DESC order: [newer, tiedHigh, tiedLow, older].
        // Cursor at tiedHigh (higher-id at shared occurrenceTime — tail of page 1).
        // Expected page 2: tiedLow first (tie-break), then older.
        List<MedicationLog> page2 = logs.findForReadAfterCursor(
                u.getId(), null, null,
                sharedOccurrence, tiedHighId,
                Pageable.ofSize(100));

        assertThat(page2).hasSize(2);
        assertThat(page2.get(0).getId()).isEqualTo(tiedLowId);     // tie-break lower-id first
        assertThat(page2.get(1).getId()).isEqualTo(older.getId()); // distinct earlier occurrence

        assertThat(page2).noneMatch(l -> l.getId().equals(newer.getId()));
        assertThat(page2).noneMatch(l -> l.getId().equals(tiedHighId));
    }

    // -------------------------------------------------------------------------
    // 12. DB CHECK — rejects invalid status (only 'taken' | 'missed')
    // -------------------------------------------------------------------------

    @Test
    void invalidStatus_rejectedByDbConstraint() {
        User u = savedUser("ml-status@example.com");
        MedicationLog l = buildLog(u.getId(), "skipped", LocalDateTime.of(2026, 7, 1, 9, 0));

        assertThatThrownBy(() -> {
            logs.save(l);
            logs.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // -------------------------------------------------------------------------
    // 13. Ad-hoc log — medicationPlanId is nullable (E6)
    // -------------------------------------------------------------------------

    @Test
    void adHocLog_medicationPlanId_isNullable() {
        // medicationPlanId = null means an ad-hoc dose with no plan (E6 spec).
        User u = savedUser("ml-adhoc@example.com");

        MedicationLog l = buildLog(u.getId(), "taken", LocalDateTime.of(2026, 7, 1, 9, 0));
        l.setMedicationPlanId(null); // explicit null

        MedicationLog saved = logs.saveAndFlush(l);

        assertThat(saved.getMedicationPlanId()).isNull();
    }

    // -------------------------------------------------------------------------
    // 14. Linked log — medicationPlanId references a plan (hard FK)
    // -------------------------------------------------------------------------

    @Test
    void linkedLog_medicationPlanId_storedCorrectly() {
        // medication_plan_id REFERENCES medication_plan(id) — hard FK (RULING 6).
        User u = savedUser("ml-linked@example.com");
        MedicationPlan plan = savedPlan(u.getId());

        MedicationLog l = buildLog(u.getId(), "taken", LocalDateTime.of(2026, 7, 1, 8, 0));
        l.setMedicationPlanId(plan.getId());

        MedicationLog saved = logs.saveAndFlush(l);

        assertThat(saved.getMedicationPlanId()).isEqualTo(plan.getId());
    }

    // -------------------------------------------------------------------------
    // 15. findAllByUserIdForExport — all rows including tombstones (PDPA ม.30/31)
    // -------------------------------------------------------------------------

    @Test
    void findAllByUserIdForExport_includesLiveAndTombstoned() {
        User u = savedUser("ml-export@example.com");

        MedicationLog live = buildLog(u.getId(), "taken",  LocalDateTime.of(2026, 7, 1, 8, 0));
        MedicationLog dead = buildLog(u.getId(), "missed", LocalDateTime.of(2026, 7, 2, 9, 0));
        logs.saveAll(List.of(live, dead));

        dead.setDeletedAt(Instant.now());
        dead.setNoteCipher(null);
        logs.saveAndFlush(dead);

        List<MedicationLog> exported = logs.findAllByUserIdForExport(u.getId());
        assertThat(exported).hasSize(2);
        assertThat(exported).anyMatch(l -> l.getDeletedAt() == null);
        assertThat(exported).anyMatch(l -> l.getDeletedAt() != null);
    }

    // -------------------------------------------------------------------------
    // 16. Sync block — createdAt, updatedAt, deletedAt, clientId
    // -------------------------------------------------------------------------

    @Test
    void syncBlock_timestamps_andClientId_storedCorrectly() {
        User u = savedUser("ml-sync@example.com");
        MedicationLog l = buildLog(u.getId());
        UUID deviceId = UUID.randomUUID();
        l.setClientId(deviceId);

        MedicationLog saved = logs.saveAndFlush(l);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getLoggedAt()).isNotNull();
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getClientId()).isEqualTo(deviceId);
        assertThat(saved.getVersion()).isNotNull();
    }
}
