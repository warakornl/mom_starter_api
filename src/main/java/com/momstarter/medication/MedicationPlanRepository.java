package com.momstarter.medication;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link MedicationPlan}.
 *
 * <p>Access patterns correspond to the sync surfaces and PDPA export:
 * <ol>
 *   <li><strong>{@code POST /sync/push} (apply path)</strong> — {@link #findByUserIdAndIdIn}
 *       loads existing rows in one query for LWW version comparison (S-A conflict check);
 *       {@link #initVersionToOne} sets version:=1 on fresh INSERT (api-contract §5).</li>
 *   <li><strong>{@code GET /sync/pull} (pull path)</strong> — {@link #findForPull} covers
 *       the steady-state delta; {@link #findForPullAfterCursor} covers cold-start continuation.
 *       Both include tombstones so deletions propagate.</li>
 *   <li><strong>Read-only REST — {@code GET /medication-plans}</strong> — {@link #findForRead}
 *       and {@link #findForReadAfterCursor} return live (non-deleted) plans ordered
 *       {@code (updated_at DESC, id DESC)}. No {@code from}/{@code to} date filter
 *       (a plan has no event bucket key — spec §A.2 / RULING 7.4).</li>
 *   <li><strong>PDPA export</strong> — {@link #findAllByUserIdForExport} returns all rows
 *       (live + tombstones) for the ม.30/31 data-portability export.</li>
 * </ol>
 *
 * <p>All queries scope by {@code user_id} to enforce ownership (IDOR prevention, spec D7).
 * Server NEVER predicates over {@link MedicationPlan#nameCipher} or {@link MedicationPlan#doseCipher}
 * — these are opaque {@code bytea} ciphertext (INV-M3 / RULING 1).
 *
 * <p>Index coverage:
 * <ul>
 *   <li>{@code ix_medication_plan__sync_pull (user_id, updated_at, id)} — pull path
 *       (forward scan for ASC, backward scan for DESC — Postgres reads both directions,
 *       no redundant DESC index needed — spec §A.2 / RULING G-5).</li>
 *   <li>{@code ix_medication_plan__deleted_at} — tombstone GC sweep.</li>
 * </ul>
 */
public interface MedicationPlanRepository extends JpaRepository<MedicationPlan, UUID> {

    // -------------------------------------------------------------------------
    // Push path — version initialisation (contract §5 pin: version:=1 on INSERT)
    // -------------------------------------------------------------------------

    /**
     * Atomically bumps {@code version} from {@code 0} to {@code 1} for a newly-inserted entity.
     *
     * <p>Called immediately after {@code saveAndFlush()} on a fresh INSERT so that the
     * wire-visible {@code version} in {@code applied[]} and the DB-stored version are both
     * {@code 1} (api-contract §5: "genuine create → server sets version:=1").
     *
     * <p>{@code clearAutomatically = true} evicts the L1 cache so subsequent loads within
     * the same transaction see the updated value.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE MedicationPlan p SET p.version = 1 WHERE p.id = :id AND p.version = 0")
    void initVersionToOne(@Param("id") UUID id);

    // -------------------------------------------------------------------------
    // Push path — batch ID lookup
    // -------------------------------------------------------------------------

    /**
     * Returns medication plans owned by {@code userId} whose ids appear in {@code ids}.
     *
     * <p>Used by the sync/push apply path to load existing rows in a single query so the
     * service can compare each record's base {@code version} against the current server
     * {@code version} (version-arbitrated LWW, S-A, sync spec §A.6).
     */
    List<MedicationPlan> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // -------------------------------------------------------------------------
    // Pull path — watermark keyset scan (ASC, includes tombstones)
    // -------------------------------------------------------------------------

    /**
     * Steady-state delta pull: all medication plans for {@code userId} with
     * {@code updated_at >= since}, ordered {@code (updated_at ASC, id ASC)}.
     *
     * <p>Includes both live rows and tombstones so deletions propagate to other devices.
     * Covered by {@code ix_medication_plan__sync_pull (user_id, updated_at, id)}.
     */
    @Query("SELECT p FROM MedicationPlan p " +
           "WHERE p.userId = :userId AND p.updatedAt >= :since " +
           "ORDER BY p.updatedAt ASC, p.id ASC")
    List<MedicationPlan> findForPull(@Param("userId") UUID userId,
                                      @Param("since") Instant since,
                                      Pageable pageable);

    /**
     * Cold-start / cursor-continuation keyset scan — resumes a paginated drain after a cursor.
     *
     * <pre>
     *   WHERE user_id = :userId AND updated_at &gt;= :since
     *     AND (updated_at &gt; :cursorUpdatedAt
     *          OR (updated_at = :cursorUpdatedAt AND id &gt; :cursorId))
     *   ORDER BY updated_at ASC, id ASC
     * </pre>
     *
     * Covered by {@code ix_medication_plan__sync_pull (user_id, updated_at, id)}.
     */
    @Query("SELECT p FROM MedicationPlan p " +
           "WHERE p.userId = :userId AND p.updatedAt >= :since " +
           "AND (p.updatedAt > :cursorUpdatedAt " +
           "     OR (p.updatedAt = :cursorUpdatedAt AND p.id > :cursorId)) " +
           "ORDER BY p.updatedAt ASC, p.id ASC")
    List<MedicationPlan> findForPullAfterCursor(@Param("userId") UUID userId,
                                                 @Param("since") Instant since,
                                                 @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                                 @Param("cursorId") UUID cursorId,
                                                 Pageable pageable);

    // -------------------------------------------------------------------------
    // Read-only REST — GET /medication-plans (live rows only, DESC keyset)
    // -------------------------------------------------------------------------

    /**
     * Returns live (non-deleted) medication plans for the given user in descending
     * keyset order {@code (updated_at DESC, id DESC)}.
     *
     * <p>Used by the read-only REST endpoint {@code GET /medication-plans}. There is
     * <strong>NO</strong> {@code from}/{@code to} date filter — a plan has no event bucket
     * key (spec §A.2 / RULING 7.4; contrast {@code GET /medication-logs} which filters
     * on {@code occurrence_time}).
     *
     * <p>Index: {@code ix_medication_plan__sync_pull (user_id, updated_at, id)} serves
     * this query via a backward scan — Postgres reads either direction, so no redundant
     * DESC index is needed (spec §A.2 / RULING G-5 CONFIRMED).
     *
     * @param userId   the authenticated user's id (IDOR scope — JWT subject, D7)
     * @param pageable page size for cursor pagination (default 100, max 500 per spec §A.2)
     * @return live plans in {@code (updatedAt DESC, id DESC)} order
     */
    @Query("SELECT p FROM MedicationPlan p " +
           "WHERE p.userId = :userId AND p.deletedAt IS NULL " +
           "ORDER BY p.updatedAt DESC, p.id DESC")
    List<MedicationPlan> findForRead(@Param("userId") UUID userId,
                                      Pageable pageable);

    /**
     * Cursor-continuation keyset scan for {@code GET /medication-plans}.
     *
     * <p>Resumes paging after the last record in the previous batch using the row-value
     * predicate {@code (updated_at, id) < (cursorUpdatedAt, cursorId)} (descending keyset).
     * Excludes soft-deleted rows (live-only read surface, spec §A.2).
     */
    @Query("SELECT p FROM MedicationPlan p " +
           "WHERE p.userId = :userId AND p.deletedAt IS NULL " +
           "AND (p.updatedAt < :cursorUpdatedAt " +
           "     OR (p.updatedAt = :cursorUpdatedAt AND p.id < :cursorId)) " +
           "ORDER BY p.updatedAt DESC, p.id DESC")
    List<MedicationPlan> findForReadAfterCursor(@Param("userId") UUID userId,
                                                 @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                                 @Param("cursorId") UUID cursorId,
                                                 Pageable pageable);

    // -------------------------------------------------------------------------
    // PDPA ม.30/31 — data export (all records, including tombstones)
    // -------------------------------------------------------------------------

    /**
     * Returns ALL medication plans for the given user including soft-deleted tombstones.
     *
     * <p>Used exclusively by the {@code GET /account/export} path (PDPA ม.30/31 portability).
     * Tombstoned rows are included: the user's right to access covers all records in the
     * pre-GC window, including those whose cipher columns have been crypto-shredded to null.
     * Ordered by {@code (updated_at ASC, id ASC)} for deterministic output.
     */
    @Query("SELECT p FROM MedicationPlan p WHERE p.userId = :userId " +
           "ORDER BY p.updatedAt ASC, p.id ASC")
    List<MedicationPlan> findAllByUserIdForExport(@Param("userId") UUID userId);
}
