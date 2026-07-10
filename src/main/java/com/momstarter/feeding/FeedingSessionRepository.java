package com.momstarter.feeding;

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
 * Repository for {@link FeedingSession}.
 *
 * <p>Access patterns:
 * <ol>
 *   <li><strong>Push apply path</strong> — {@link #findByUserIdAndIdIn} for batch existence check
 *       (immutable-event idempotency); {@link #initVersionToOne} for version:=1 sentinel.</li>
 *   <li><strong>Sync pull</strong> — {@link #findForPull} / {@link #findForPullAfterCursor}
 *       keyset scans ordered by {@code (updated_at ASC, id ASC)}. Includes tombstones.</li>
 *   <li><strong>GET /v1/feeding-sessions</strong> — live rows keyed on {@code started_at}
 *       via {@link #findHistory} / {@link #findHistoryAfterCursor}.</li>
 * </ol>
 *
 * <p>DUAL-health collection; gated by {@code general_health} + {@code infant_feeding}
 * (per-collection, handled in {@code FeedingSessionSyncCollection}).
 * All queries scope by {@code user_id} (IDOR prevention).
 */
public interface FeedingSessionRepository extends JpaRepository<FeedingSession, UUID> {

    // -------------------------------------------------------------------------
    // Push path — version initialisation (contract §5 pin: version:=1 on INSERT)
    // -------------------------------------------------------------------------

    /**
     * Bumps {@code version} from {@code 0} to {@code 1} for a newly-inserted entity.
     * Called immediately after {@code saveAndFlush()} on a fresh INSERT so the
     * wire-visible {@code version} in {@code applied[]} is {@code 1}.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FeedingSession s SET s.version = 1 WHERE s.id = :id AND s.version = 0")
    void initVersionToOne(@Param("id") UUID id);

    // -------------------------------------------------------------------------
    // Push path — batch id lookup
    // -------------------------------------------------------------------------

    /** Returns sessions owned by {@code userId} whose ids appear in {@code ids}. */
    List<FeedingSession> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // -------------------------------------------------------------------------
    // Pull path — watermark keyset scan (updated_at ASC, id ASC)
    // -------------------------------------------------------------------------

    /**
     * Steady-state delta pull: sessions with {@code updated_at >= since}, ordered
     * {@code (updated_at ASC, id ASC)}. Includes tombstones.
     * Covered by {@code ix_feeding_session__sync_pull (user_id, updated_at, id)}.
     */
    @Query("SELECT s FROM FeedingSession s " +
           "WHERE s.userId = :userId AND s.updatedAt >= :since " +
           "ORDER BY s.updatedAt ASC, s.id ASC")
    List<FeedingSession> findForPull(@Param("userId") UUID userId,
                                     @Param("since") Instant since,
                                     Pageable pageable);

    /**
     * Cold-start cursor-continuation: resumes after {@code (cursorUpdatedAt, cursorId)}.
     * Covered by {@code ix_feeding_session__sync_pull}.
     */
    @Query("SELECT s FROM FeedingSession s " +
           "WHERE s.userId = :userId AND s.updatedAt >= :since " +
           "AND (s.updatedAt > :cursorUpdatedAt " +
           "     OR (s.updatedAt = :cursorUpdatedAt AND s.id > :cursorId)) " +
           "ORDER BY s.updatedAt ASC, s.id ASC")
    List<FeedingSession> findForPullAfterCursor(@Param("userId") UUID userId,
                                                 @Param("since") Instant since,
                                                 @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                                 @Param("cursorId") UUID cursorId,
                                                 Pageable pageable);

    // -------------------------------------------------------------------------
    // Read-only REST — GET /v1/feeding-sessions history view
    // -------------------------------------------------------------------------

    /**
     * Live sessions in {@code (started_at ASC, id ASC)} order.
     * Optional {@code from}/{@code to} filter on {@link FeedingSession#getStartedAt()}
     * (floating-civil bucket key, FLAG-1). Tombstones excluded.
     * Covered by {@code ix_feeding_session__history (user_id, started_at, id)}.
     */
    @Query("SELECT s FROM FeedingSession s " +
           "WHERE s.userId = :userId AND s.deletedAt IS NULL " +
           "AND (:from IS NULL OR s.startedAt >= :from) " +
           "AND (:to IS NULL OR s.startedAt <= :to) " +
           "ORDER BY s.startedAt ASC, s.id ASC")
    List<FeedingSession> findHistory(@Param("userId") UUID userId,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to,
                                     Pageable pageable);

    /**
     * Cursor-continuation for {@code GET /v1/feeding-sessions}.
     * Resumes after {@code (cursorStartedAt, cursorId)}.
     */
    @Query("SELECT s FROM FeedingSession s " +
           "WHERE s.userId = :userId AND s.deletedAt IS NULL " +
           "AND (:from IS NULL OR s.startedAt >= :from) " +
           "AND (:to IS NULL OR s.startedAt <= :to) " +
           "AND (s.startedAt > :cursorStartedAt " +
           "     OR (s.startedAt = :cursorStartedAt AND s.id > :cursorId)) " +
           "ORDER BY s.startedAt ASC, s.id ASC")
    List<FeedingSession> findHistoryAfterCursor(@Param("userId") UUID userId,
                                                 @Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to,
                                                 @Param("cursorStartedAt") LocalDateTime cursorStartedAt,
                                                 @Param("cursorId") UUID cursorId,
                                                 Pageable pageable);
}
