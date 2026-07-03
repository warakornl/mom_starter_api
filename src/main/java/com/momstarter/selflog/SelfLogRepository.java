package com.momstarter.selflog;

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
 * Repository for {@link SelfLog}.
 *
 * <p>Access patterns correspond to three surfaces:
 * <ol>
 *   <li><strong>{@code POST /sync/push} (apply path)</strong> — {@link #findByUserIdAndIdIn}
 *       for batch existence check + immutable-event idempotency (sync spec §A.8);
 *       {@link #initVersionToOne} for the version:=1 create sentinel (contract §5).</li>
 *   <li><strong>{@code GET /sync/pull} (pull path)</strong> — safe-window keyset scan
 *       ({@link #findForPull}) and cold-start cursor continuation
 *       ({@link #findForPullAfterCursor}). Includes tombstones so deletions propagate.</li>
 *   <li><strong>{@code GET /self-logs} (read-only REST)</strong> — live rows only, keyed on
 *       {@link SelfLog#loggedAt} (floating-civil bucket key, FLAG-1 / D5) with optional
 *       {@code metricType} and date-range filters, ordered {@code (loggedAt DESC, id DESC)}
 *       ({@link #findForRead} / {@link #findForReadAfterCursor}).</li>
 * </ol>
 *
 * <p>MOTHER-health collection; gated by {@code general_health} (per-collection) +
 * {@code cloud_storage} (whole-batch) on {@code sync/push} (spec D6 / §A.1).
 *
 * <p>All queries scope by {@code user_id} to enforce ownership (IDOR prevention, spec D7).
 * The server NEVER filters or predicates over the {@code bytea} value columns
 * (INV-S2 / G4 — opaque ciphertext, ADR Decision 1).
 */
public interface SelfLogRepository extends JpaRepository<SelfLog, UUID> {

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
    @Query("UPDATE SelfLog s SET s.version = 1 WHERE s.id = :id AND s.version = 0")
    void initVersionToOne(@Param("id") UUID id);

    // -------------------------------------------------------------------------
    // Push path — batch id lookup
    // -------------------------------------------------------------------------

    /**
     * Returns self-logs owned by {@code userId} whose ids appear in {@code ids}.
     *
     * <p>Used by the sync/push apply path to load existing rows in one query, enabling the
     * immutable-event idempotency check (existing live → no-op; not present → INSERT).
     */
    List<SelfLog> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // -------------------------------------------------------------------------
    // Pull path — watermark keyset scan
    // -------------------------------------------------------------------------

    /**
     * Steady-state delta pull: self-logs for {@code userId} with
     * {@code updated_at >= since}, ordered {@code (updated_at ASC, id ASC)}.
     *
     * <p>Includes tombstones so deletions propagate to other devices.
     * Covered by {@code ix_self_log__sync_pull (user_id, updated_at, id)}.
     */
    @Query("SELECT s FROM SelfLog s " +
           "WHERE s.userId = :userId AND s.updatedAt >= :since " +
           "ORDER BY s.updatedAt ASC, s.id ASC")
    List<SelfLog> findForPull(@Param("userId") UUID userId,
                               @Param("since") Instant since,
                               Pageable pageable);

    /**
     * Cold-start cursor-continuation keyset scan.
     *
     * <p>Resumes a paginated drain after a cursor using the row-value predicate
     * {@code (updated_at, id) > (cursorUpdatedAt, cursorId)}.
     * Covered by {@code ix_self_log__sync_pull (user_id, updated_at, id)}.
     */
    @Query("SELECT s FROM SelfLog s " +
           "WHERE s.userId = :userId AND s.updatedAt >= :since " +
           "AND (s.updatedAt > :cursorUpdatedAt " +
           "     OR (s.updatedAt = :cursorUpdatedAt AND s.id > :cursorId)) " +
           "ORDER BY s.updatedAt ASC, s.id ASC")
    List<SelfLog> findForPullAfterCursor(@Param("userId") UUID userId,
                                          @Param("since") Instant since,
                                          @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                          @Param("cursorId") UUID cursorId,
                                          Pageable pageable);

    // -------------------------------------------------------------------------
    // Read-only REST — GET /self-logs history view (spec §A.2 / ADR Decision 2)
    // -------------------------------------------------------------------------

    /**
     * Returns live (non-deleted) self-logs for the given user, optionally filtered by
     * {@code metricType} and a civil date range on {@link SelfLog#loggedAt} (FLAG-1 / D5).
     *
     * <p>Ordering: {@code (loggedAt DESC, id DESC)} — most-recent first per calendar bucket key.
     *
     * <p>{@code null} parameters are treated as open / unfiltered:
     * <ul>
     *   <li>{@code metricType = null} → no metric filter (all five metric types returned).</li>
     *   <li>{@code from = null} → no lower bound on {@code logged_at}.</li>
     *   <li>{@code to = null} → no upper bound on {@code logged_at}.</li>
     * </ul>
     *
     * <p>Index coverage:
     * <ul>
     *   <li>{@code metricType} absent → {@code ix_self_log__user_time (user_id, logged_at, id)}.</li>
     *   <li>{@code metricType} present → {@code ix_self_log__user_metric_time (user_id, metric_type, logged_at, id)}.</li>
     * </ul>
     *
     * <p>The server NEVER predicates over {@code value_numeric}/{@code value_numeric_secondary}/
     * {@code value_text}/{@code note_cipher} — these are opaque {@code bytea} ciphertext (INV-S2 / G4).
     *
     * @param userId     the authenticated user's id (IDOR scope — JWT subject, D7)
     * @param metricType optional metricType filter (null → all); caller must validate the enum before calling
     * @param from       lower bound on {@code logged_at} (inclusive, nullable → no lower bound)
     * @param to         upper bound on {@code logged_at} (inclusive, nullable → no upper bound)
     * @param pageable   page size for cursor pagination
     * @return live logs in {@code (loggedAt DESC, id DESC)} order
     */
    @Query("SELECT s FROM SelfLog s " +
           "WHERE s.userId = :userId AND s.deletedAt IS NULL " +
           "AND (:metricType IS NULL OR s.metricType = :metricType) " +
           "AND (:from IS NULL OR s.loggedAt >= :from) " +
           "AND (:to IS NULL OR s.loggedAt <= :to) " +
           "ORDER BY s.loggedAt DESC, s.id DESC")
    List<SelfLog> findForRead(@Param("userId") UUID userId,
                               @Param("metricType") String metricType,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to,
                               Pageable pageable);

    /**
     * Cursor-continuation keyset scan for {@code GET /self-logs}.
     *
     * <p>Resumes paging after the last record in the previous batch using the row-value
     * predicate {@code (logged_at, id) < (cursorLoggedAt, cursorId)} (descending keyset).
     *
     * <p>Applies the same {@code metricType} + {@code from}/{@code to} filters as
     * {@link #findForRead} so the cursor is consistent with the original query.
     */
    @Query("SELECT s FROM SelfLog s " +
           "WHERE s.userId = :userId AND s.deletedAt IS NULL " +
           "AND (:metricType IS NULL OR s.metricType = :metricType) " +
           "AND (:from IS NULL OR s.loggedAt >= :from) " +
           "AND (:to IS NULL OR s.loggedAt <= :to) " +
           "AND (s.loggedAt < :cursorLoggedAt " +
           "     OR (s.loggedAt = :cursorLoggedAt AND s.id < :cursorId)) " +
           "ORDER BY s.loggedAt DESC, s.id DESC")
    List<SelfLog> findForReadAfterCursor(@Param("userId") UUID userId,
                                          @Param("metricType") String metricType,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          @Param("cursorLoggedAt") LocalDateTime cursorLoggedAt,
                                          @Param("cursorId") UUID cursorId,
                                          Pageable pageable);
}
