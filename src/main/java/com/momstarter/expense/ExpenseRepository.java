package com.momstarter.expense;

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
 * Repository for {@link Expense}.
 *
 * <p>Access patterns correspond to the two sync surfaces and PDPA export:
 * <ol>
 *   <li><strong>{@code POST /sync/push} (apply path)</strong> — {@link #findByUserIdAndIdIn}
 *       loads existing rows in one query for version comparison (LWW, S-A conflict check).</li>
 *   <li><strong>{@code GET /sync/pull} (pull path)</strong> — {@link #findForPull} covers
 *       the steady-state delta; {@link #findForPullAfterCursor} covers cold-start continuation.</li>
 *   <li><strong>Read-only REST list</strong> — {@link #findByUserIdAndDeletedAtIsNull}
 *       returns live (non-deleted) expenses in keyset order.</li>
 *   <li><strong>PDPA export</strong> — {@link #findAllByUserIdForExport} returns all rows
 *       (live + tombstones) for the ม.30/31 data-portability export.</li>
 * </ol>
 */
public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    // -------------------------------------------------------------------------
    // Push path — version initialisation (contract §5 pin: version:=1 on INSERT)
    // -------------------------------------------------------------------------

    /**
     * Atomically bumps {@code version} from 0 to 1 for a newly INSERTed entity.
     *
     * <p>Called immediately after {@code saveAndFlush()} on a fresh INSERT so that the
     * wire-visible {@code version} in {@code applied[]} and the DB-stored version are both
     * {@code 1}, satisfying api-contract §5: "genuine create → server sets {@code version:=1}".
     *
     * <p>{@code clearAutomatically = true} evicts the L1 cache so subsequent loads within
     * the same transaction see the updated value.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Expense e SET e.version = 1 WHERE e.id = :id AND e.version = 0")
    void initVersionToOne(@Param("id") UUID id);

    // -------------------------------------------------------------------------
    // Push path — batch ID lookup
    // -------------------------------------------------------------------------

    /**
     * Returns expenses owned by {@code userId} whose ids appear in {@code ids}.
     *
     * <p>Used by the sync/push apply path to load existing rows in a single query so the
     * service can compare each record's base {@code version} against the current server
     * {@code version} (version-arbitrated LWW, S-A, sync spec §A.6).
     */
    List<Expense> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // -------------------------------------------------------------------------
    // Pull path — watermark keyset scan
    // -------------------------------------------------------------------------

    /**
     * Steady-state delta pull: all expenses for {@code userId} with
     * {@code updated_at >= since}, ordered by {@code (updated_at ASC, id ASC)}.
     *
     * <p>Includes both live rows and tombstones so deletions propagate to other devices.
     */
    @Query("SELECT e FROM Expense e WHERE e.userId = :userId AND e.updatedAt >= :since " +
           "ORDER BY e.updatedAt ASC, e.id ASC")
    List<Expense> findForPull(@Param("userId") UUID userId,
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
     * The existing {@code ix_expenses__sync_pull (user_id, updated_at, id)} serves this query.
     */
    @Query("SELECT e FROM Expense e WHERE e.userId = :userId AND e.updatedAt >= :since " +
           "AND (e.updatedAt > :cursorUpdatedAt " +
           "     OR (e.updatedAt = :cursorUpdatedAt AND e.id > :cursorId)) " +
           "ORDER BY e.updatedAt ASC, e.id ASC")
    List<Expense> findForPullAfterCursor(@Param("userId") UUID userId,
                                          @Param("since") Instant since,
                                          @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                          @Param("cursorId") UUID cursorId,
                                          Pageable pageable);

    // -------------------------------------------------------------------------
    // Read-only REST — live list
    // -------------------------------------------------------------------------

    /**
     * Returns live (non-deleted) expenses for the given user in keyset order.
     *
     * <p>Used by read-only REST (e.g., {@code GET /expenses}). Category filtering
     * (if ever needed) is a residual filter in the service layer.
     */
    @Query("SELECT e FROM Expense e WHERE e.userId = :userId AND e.deletedAt IS NULL " +
           "ORDER BY e.updatedAt ASC, e.id ASC")
    List<Expense> findByUserIdAndDeletedAtIsNull(@Param("userId") UUID userId,
                                                  Pageable pageable);

    // -------------------------------------------------------------------------
    // PDPA ม.30/31 — data export (all records, including tombstones)
    // -------------------------------------------------------------------------

    /**
     * Returns ALL expenses for the given user including soft-deleted tombstones.
     *
     * <p>Used exclusively by the {@code GET /account/export} path (PDPA ม.30/31 portability).
     * Ordered by {@code (updated_at ASC, id ASC)} for deterministic output.
     */
    @Query("SELECT e FROM Expense e WHERE e.userId = :userId " +
           "ORDER BY e.updatedAt ASC, e.id ASC")
    List<Expense> findAllByUserIdForExport(@Param("userId") UUID userId);
}
