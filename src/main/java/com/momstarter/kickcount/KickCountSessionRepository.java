package com.momstarter.kickcount;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link KickCountSession}.
 *
 * <p>Access patterns exposed here correspond to three surfaces:
 * <ol>
 *   <li><strong>{@code POST /sync/push} (apply path)</strong> — {@link #findByUserIdAndIdIn}
 *       for batch existence check + immutable-event idempotency (sync spec §A.8);
 *       {@link #initVersionToOne} for the version:=1 create sentinel (contract §5).</li>
 *   <li><strong>{@code GET /sync/pull} (pull path)</strong> — safe-window keyset scan
 *       ({@link #findForPull}) and cold-start cursor continuation
 *       ({@link #findForPullAfterCursor}).  Includes tombstones so deletions propagate.</li>
 *   <li><strong>{@code GET /kick-count-sessions} (read-only REST)</strong> — live rows only,
 *       keyed on {@code started_at} (floating-civil bucket key) with optional range filter
 *       ({@link #findHistory} / {@link #findHistoryAfterCursor}).</li>
 * </ol>
 *
 * <p>MOTHER-health collection; gated by {@code general_health} (per-collection) +
 * {@code cloud_storage} (whole-batch) on {@code sync/push}.
 *
 * <p>All queries scope by {@code user_id} to enforce ownership (IDOR prevention).
 */
public interface KickCountSessionRepository extends JpaRepository<KickCountSession, UUID> {

    // -------------------------------------------------------------------------
    // Push path — version initialisation (contract §5 pin: version:=1 on INSERT)
    // -------------------------------------------------------------------------

    /**
     * Atomically bumps {@code version} from {@code 0} to {@code 1} for a newly-inserted entity.
     *
     * <p>Called immediately after {@code saveAndFlush()} on a fresh INSERT so that the
     * wire-visible {@code version} in {@code applied[]} and the DB-stored version are both
     * {@code 1} (api-contract §5: "genuine create → version:=1").
     *
     * <p>{@code clearAutomatically = true} evicts the L1 cache so subsequent loads within the
     * same transaction see the updated value.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE KickCountSession s SET s.version = 1 WHERE s.id = :id AND s.version = 0")
    void initVersionToOne(@Param("id") UUID id);

    // -------------------------------------------------------------------------
    // Push path — batch id lookup
    // -------------------------------------------------------------------------

    /**
     * Returns kick-count sessions owned by {@code userId} whose ids appear in {@code ids}.
     *
     * <p>Used by the sync/push apply path to load existing rows in one query, enabling the
     * immutable-event idempotency check (existing live → no-op; not present → INSERT).
     */
    List<KickCountSession> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // -------------------------------------------------------------------------
    // Pull path — watermark keyset scan
    // -------------------------------------------------------------------------

    /**
     * Steady-state delta pull: sessions for {@code userId} with
     * {@code updated_at >= since}, ordered {@code (updated_at ASC, id ASC)}.
     *
     * <p>Includes tombstones so deletions propagate to other devices.
     * Covered by {@code ix_kick_count_session__sync_pull (user_id, updated_at, id)}.
     */
    @Query("SELECT s FROM KickCountSession s " +
           "WHERE s.userId = :userId AND s.updatedAt >= :since " +
           "ORDER BY s.updatedAt ASC, s.id ASC")
    List<KickCountSession> findForPull(@Param("userId") UUID userId,
                                       @Param("since") Instant since,
                                       Pageable pageable);

    /**
     * Cold-start cursor-continuation keyset scan.
     *
     * <p>Resumes a paginated drain after a cursor using the row-value predicate
     * {@code (updated_at, id) > (cursorUpdatedAt, cursorId)}.
     * Covered by the same {@code ix_kick_count_session__sync_pull} index.
     */
    @Query("SELECT s FROM KickCountSession s " +
           "WHERE s.userId = :userId AND s.updatedAt >= :since " +
           "AND (s.updatedAt > :cursorUpdatedAt " +
           "     OR (s.updatedAt = :cursorUpdatedAt AND s.id > :cursorId)) " +
           "ORDER BY s.updatedAt ASC, s.id ASC")
    List<KickCountSession> findForPullAfterCursor(@Param("userId") UUID userId,
                                                   @Param("since") Instant since,
                                                   @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                                   @Param("cursorId") UUID cursorId,
                                                   Pageable pageable);

    // -------------------------------------------------------------------------
    // Read-only REST — GET /kick-count-sessions history view
    // -------------------------------------------------------------------------

    /**
     * Returns live (non-deleted) completed sessions for the given user,
     * ordered by {@code (started_at ASC, id ASC)}.
     *
     * <p>Optional {@code from}/{@code to} filter on {@link KickCountSession#startedAt}
     * (floating-civil bucket key, FLAG-1 — data-model §3.13 / §5).
     * {@code null} bounds are treated as open (no lower/upper limit).
     *
     * <p>Covered by {@code ix_kick_count_session__history (user_id, started_at, id)}.
     *
     * <p>Server-side rows are always {@code status = completed} (DB CHECK constraint);
     * no explicit status filter is needed.
     *
     * @param userId   the authenticated user's id (IDOR scope)
     * @param from     lower bound on {@code started_at} (inclusive, nullable → no lower bound)
     * @param to       upper bound on {@code started_at} (inclusive, nullable → no upper bound)
     * @param pageable page size for cursor pagination
     * @return live sessions in {@code (started_at ASC, id ASC)} order
     */
    @Query("SELECT s FROM KickCountSession s " +
           "WHERE s.userId = :userId AND s.deletedAt IS NULL " +
           "AND (:from IS NULL OR s.startedAt >= :from) " +
           "AND (:to IS NULL OR s.startedAt <= :to) " +
           "ORDER BY s.startedAt ASC, s.id ASC")
    List<KickCountSession> findHistory(@Param("userId") UUID userId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       Pageable pageable);

    /**
     * Cursor-continuation keyset scan for {@code GET /kick-count-sessions}.
     *
     * <p>Resumes paging after the last record in the previous batch using the row-value
     * predicate {@code (started_at, id) > (cursorStartedAt, cursorId)}.
     */
    @Query("SELECT s FROM KickCountSession s " +
           "WHERE s.userId = :userId AND s.deletedAt IS NULL " +
           "AND (:from IS NULL OR s.startedAt >= :from) " +
           "AND (:to IS NULL OR s.startedAt <= :to) " +
           "AND (s.startedAt > :cursorStartedAt " +
           "     OR (s.startedAt = :cursorStartedAt AND s.id > :cursorId)) " +
           "ORDER BY s.startedAt ASC, s.id ASC")
    List<KickCountSession> findHistoryAfterCursor(@Param("userId") UUID userId,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to,
                                                   @Param("cursorStartedAt") LocalDateTime cursorStartedAt,
                                                   @Param("cursorId") UUID cursorId,
                                                   Pageable pageable);

    // -------------------------------------------------------------------------
    // PDPA ม.30/31 — data export (all records, including tombstones)
    // -------------------------------------------------------------------------

    /**
     * Returns ALL kick-count sessions for the given user, including soft-deleted tombstones.
     * Used exclusively by the {@code GET /account/export} path (PDPA ม.30/31 portability).
     *
     * <p>{@code note_cipher} is a field on the entity but is NEVER mapped into the export DTO
     * — see {@link com.momstarter.account.dto.export.KickCountSessionExportEntry} Javadoc.
     *
     * <p>Size note: bounded by daily sessions during pregnancy (≈270 max at 9 months daily use).
     *
     * @param userId the authenticated user's id (IDOR scope enforced by the service)
     * @return all sessions in {@code (started_at ASC, id ASC)} order
     */
    @Query("SELECT s FROM KickCountSession s WHERE s.userId = :userId " +
           "ORDER BY s.startedAt ASC, s.id ASC")
    List<KickCountSession> findAllByUserIdForExport(@Param("userId") UUID userId);
}
