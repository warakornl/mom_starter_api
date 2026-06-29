package com.momstarter.supply;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link SupplyItem}.
 *
 * <p>Access patterns exposed here correspond to the two sync surfaces:
 * <ol>
 *   <li><strong>{@code POST /sync/push} (apply path)</strong> — the sync service looks up
 *       existing rows by {@code (userId, id)} to decide insert vs. update and to perform
 *       the version-arbitrated LWW conflict check (S-A: base version &lt; current → server_won).
 *       {@link #findByUserIdAndIdIn} covers the batch version lookup.</li>
 *   <li><strong>{@code GET /sync/pull} (pull path)</strong> — the pull query is a safe-window
 *       keyset scan: {@code WHERE user_id = ? AND updated_at >= (watermark − safeWindow)
 *       ORDER BY updated_at ASC, id ASC}. {@link #findForPull} covers the steady-state delta.
 *       Cold-start cursor continuation (row-value keyset form) requires a custom {@code @Query}
 *       that is the sync engine's concern — see the note on {@link #findForPullAfterCursor}.</li>
 *   <li><strong>{@code GET /supply-items} (read-only REST list)</strong> —
 *       {@link #findByUserIdAndDeletedAtIsNull} serves the live-items list; the {@code Pageable}
 *       carries the {@code (updated_at, id)} keyset limit for cursor pagination.</li>
 * </ol>
 */
public interface SupplyItemRepository extends JpaRepository<SupplyItem, UUID> {

    // -------------------------------------------------------------------------
    // Push path — version check + batch ID lookup
    // -------------------------------------------------------------------------

    /**
     * Returns supply items owned by {@code userId} whose ids appear in {@code ids}.
     *
     * <p>Used by the sync/push apply path to load existing rows in a single query so the
     * service can compare each record's base {@code version} (from the push payload) against
     * the current server {@code version} (version-arbitrated LWW, S-A, sync spec §A.6).
     *
     * @param userId the authenticated user's id
     * @param ids    the set of record ids included in the push batch (both {@code created[]}
     *               and {@code updated[]} bucket records)
     * @return the matching live or tombstoned rows (empty list if none match)
     */
    List<SupplyItem> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // -------------------------------------------------------------------------
    // Pull path — watermark keyset scan
    // -------------------------------------------------------------------------

    /**
     * Steady-state delta pull: all supply items for {@code userId} with
     * {@code updated_at >= since}, ordered by {@code (updated_at ASC, id ASC)}.
     *
     * <p>This is the safe-window keyset scan for {@code GET /sync/pull} (sync spec §B.3):
     * <pre>
     *   WHERE user_id = :userId AND updated_at &gt;= :since
     *   ORDER BY updated_at ASC, id ASC
     * </pre>
     * Includes both live rows and tombstones ({@code deleted_at IS NOT NULL}) so deletions
     * propagate to other devices.
     *
     * <p>Pass a {@link Pageable} with a page size to bound the response (default limit 1000,
     * max 5000 across all collections — sync spec §B.1). Pass {@link Pageable#unpaged()} for
     * a full unbounded scan (steady-state, single-page response).
     *
     * @param userId  the authenticated user's id
     * @param since   the pull watermark minus the safe window (typically {@code watermark − 5s})
     * @param pageable page size limit; {@link Pageable#unpaged()} for no limit
     * @return rows in {@code (updated_at ASC, id ASC)} order
     */
    @Query("SELECT s FROM SupplyItem s WHERE s.userId = :userId AND s.updatedAt >= :since " +
           "ORDER BY s.updatedAt ASC, s.id ASC")
    List<SupplyItem> findForPull(@Param("userId") UUID userId,
                                 @Param("since") Instant since,
                                 Pageable pageable);

    /**
     * Cold-start / cursor-continuation keyset scan — resumes a paginated drain after a cursor.
     *
     * <p>Used by the cold-start batched drain (sync spec §B.4 / database-schema §4.2): after
     * the first batch, subsequent batches resume with the row-value predicate
     * {@code (updated_at, id) > (cursorUpdatedAt, cursorId)} within the same {@code since}
     * window:
     * <pre>
     *   WHERE user_id = :userId AND updated_at &gt;= :since
     *     AND (updated_at &gt; :cursorUpdatedAt
     *          OR (updated_at = :cursorUpdatedAt AND id &gt; :cursorId))
     *   ORDER BY updated_at ASC, id ASC
     * </pre>
     * The existing {@code ix_supply_items__sync_pull (user_id, updated_at, id)} serves this
     * query index-only (no new index required — database-schema §4.2 / sync spec §B.4).
     * {@code since} is held FIXED for the entire drain (sync spec §B.4).
     *
     * @param userId          the authenticated user's id
     * @param since           the watermark minus safe window — held FIXED for the drain
     * @param cursorUpdatedAt {@code updated_at} of the last record in the previous batch
     * @param cursorId        {@code id} of the last record in the previous batch
     * @param pageable        page size (batch limit)
     * @return next batch in keyset order
     */
    @Query("SELECT s FROM SupplyItem s WHERE s.userId = :userId AND s.updatedAt >= :since " +
           "AND (s.updatedAt > :cursorUpdatedAt " +
           "     OR (s.updatedAt = :cursorUpdatedAt AND s.id > :cursorId)) " +
           "ORDER BY s.updatedAt ASC, s.id ASC")
    List<SupplyItem> findForPullAfterCursor(@Param("userId") UUID userId,
                                             @Param("since") Instant since,
                                             @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                             @Param("cursorId") UUID cursorId,
                                             Pageable pageable);

    // -------------------------------------------------------------------------
    // Read-only REST — GET /supply-items live list
    // -------------------------------------------------------------------------

    /**
     * Returns live (non-deleted) supply items for the given user, in
     * {@code (updated_at ASC, id ASC)} keyset order for cursor pagination.
     *
     * <p>Used by {@code GET /supply-items} (read-only REST surface). The optional
     * {@code ?category=} filter is a residual filter applied by the service layer AFTER
     * this query — it is intentionally NOT index-covered (at MVP supply-list cardinality
     * of tens of items per user there is no benefit; database-schema §1.11).
     *
     * @param userId   the authenticated user's id
     * @param pageable page size + offset for cursor-based pagination
     * @return live items in keyset order
     */
    @Query("SELECT s FROM SupplyItem s WHERE s.userId = :userId AND s.deletedAt IS NULL " +
           "ORDER BY s.updatedAt ASC, s.id ASC")
    List<SupplyItem> findByUserIdAndDeletedAtIsNull(@Param("userId") UUID userId,
                                                     Pageable pageable);
}
