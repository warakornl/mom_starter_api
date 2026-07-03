package com.momstarter.reminder;

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
 * Repository for {@link Reminder}.
 *
 * <p>Access patterns mirror {@code SupplyItemRepository} (the engine-validator pattern):
 * <ol>
 *   <li><strong>{@code POST /sync/push} (apply path)</strong> — batch id lookup for
 *       version-arbitrated LWW conflict check (S-A). {@link #findByUserIdAndIdIn}.</li>
 *   <li><strong>{@code GET /sync/pull} (pull path)</strong> — safe-window keyset delta
 *       ({@link #findForPull}) and cold-start cursor continuation
 *       ({@link #findForPullAfterCursor}). Keyset: {@code (updated_at ASC, id ASC)}.</li>
 *   <li><strong>{@code GET /reminders} (read-only REST)</strong> — live items only
 *       ({@link #findByUserIdAndDeletedAtIsNull}).</li>
 * </ol>
 */
public interface ReminderRepository extends JpaRepository<Reminder, UUID> {

    // -------------------------------------------------------------------------
    // Push path — version initialisation (contract §5 pin: version:=1 on INSERT)
    // -------------------------------------------------------------------------

    /**
     * Bumps {@code version} from {@code 0} to {@code 1} for a newly INSERTed entity.
     * Called immediately after {@code saveAndFlush()} on a fresh INSERT so the wire-visible
     * {@code version} in {@code applied[]} is {@code 1} (api-contract §5).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Reminder r SET r.version = 1 WHERE r.id = :id AND r.version = 0")
    void initVersionToOne(@Param("id") UUID id);

    // -------------------------------------------------------------------------
    // Push path — batch id lookup
    // -------------------------------------------------------------------------

    /**
     * Returns reminders owned by {@code userId} whose ids appear in {@code ids}.
     * Used by the sync/push apply path to load existing rows for version comparison
     * (version-arbitrated LWW S-A, sync spec §A.6).
     */
    List<Reminder> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // -------------------------------------------------------------------------
    // Pull path — watermark keyset scan
    // -------------------------------------------------------------------------

    /**
     * Steady-state delta pull: all reminders for {@code userId} with
     * {@code updated_at >= since}, ordered by {@code (updated_at ASC, id ASC)}.
     * Includes tombstones so deletions propagate to other devices.
     */
    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId AND r.updatedAt >= :since " +
           "ORDER BY r.updatedAt ASC, r.id ASC")
    List<Reminder> findForPull(@Param("userId") UUID userId,
                               @Param("since") Instant since,
                               Pageable pageable);

    /**
     * Cold-start cursor-continuation keyset scan — resumes a paginated drain after a cursor.
     * Predicate: {@code updated_at >= since AND (updated_at > cursorUpdatedAt
     * OR (updated_at = cursorUpdatedAt AND id > cursorId))}.
     * Reuses {@code ix_reminders__sync_pull (user_id, updated_at, id)} — no new index required.
     */
    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId AND r.updatedAt >= :since " +
           "AND (r.updatedAt > :cursorUpdatedAt " +
           "     OR (r.updatedAt = :cursorUpdatedAt AND r.id > :cursorId)) " +
           "ORDER BY r.updatedAt ASC, r.id ASC")
    List<Reminder> findForPullAfterCursor(@Param("userId") UUID userId,
                                          @Param("since") Instant since,
                                          @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                          @Param("cursorId") UUID cursorId,
                                          Pageable pageable);

    // -------------------------------------------------------------------------
    // Read-only REST — GET /reminders live list
    // -------------------------------------------------------------------------

    /**
     * Returns live (non-deleted) reminders for the given user, in
     * {@code (updated_at ASC, id ASC)} keyset order for cursor pagination.
     * Used by {@code GET /reminders} (read-only REST surface).
     */
    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId AND r.deletedAt IS NULL " +
           "ORDER BY r.updatedAt ASC, r.id ASC")
    List<Reminder> findByUserIdAndDeletedAtIsNull(@Param("userId") UUID userId,
                                                   Pageable pageable);

    // -------------------------------------------------------------------------
    // PDPA ม.30/31 — data export (all records, including tombstones)
    // -------------------------------------------------------------------------

    /**
     * Returns ALL reminders for the given user, including soft-deleted tombstones.
     * Used exclusively by the {@code GET /account/export} path (PDPA ม.30/31 portability).
     *
     * @param userId the authenticated user's id (IDOR scope enforced by the service)
     * @return all live and tombstoned reminders in {@code (updated_at ASC, id ASC)} order
     */
    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId " +
           "ORDER BY r.updatedAt ASC, r.id ASC")
    List<Reminder> findAllByUserIdForExport(@Param("userId") UUID userId);
}
