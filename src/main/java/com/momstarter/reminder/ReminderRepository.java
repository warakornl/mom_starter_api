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
    // Pregnancy-loss write path — reminder sweep (LOSS-INV-3/4/5/6, functional-spec §6)
    // -------------------------------------------------------------------------

    /**
     * Forward sweep (loss-event, functional-spec §6.1 / data-model §5): deactivates every
     * reminder NOT on the survives-{@code ended} allow-list for the given user, in the SAME
     * DB transaction as the {@code lifecycle -> 'ended'} write (LOSS-INV-3 atomicity — caller
     * MUST invoke this inside an existing {@code @Transactional} method, never standalone).
     *
     * <p>Predicate: {@code user_id=? AND survives_ended=false AND active=true AND
     * deleted_at IS NULL} (exact forward-sweep predicate, data-model L516 / functional-spec §6.1).
     * Sets the reversible tombstone marker ({@code deactivated_by='loss_event'},
     * {@code deactivated_at=now()}), bumps {@code version}, and stamps {@code updated_at}.
     *
     * <p><strong>Reversible soft marker only</strong> (LOSS-INV-4) — NOT a {@code deleted_at}
     * hard-delete and NOT a crypto-shred; the source rows are fully retained.
     *
     * @param userId the caller's own user id (authz scope — functional-spec §7.7)
     * @param now    the transaction's server-authoritative instant (single clock read per
     *               transaction, so every swept row shares one {@code deactivated_at})
     * @return number of reminder rows deactivated by this sweep
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Reminder r SET r.active = false, r.deactivatedBy = 'loss_event', " +
           "r.deactivatedAt = :now, r.updatedAt = :now, r.version = r.version + 1 " +
           "WHERE r.userId = :userId AND r.survivesEnded = false AND r.active = true " +
           "AND r.deletedAt IS NULL")
    int sweepDeactivateOnLossEvent(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Reverse sweep (reopen, functional-spec §6.2 / data-model §5): re-activates exactly the
     * reminders the loss-event sweep deactivated, in the SAME DB transaction as the
     * {@code lifecycle -> 'pregnant'} + {@code loss_date := NULL} write (LOSS-INV-3/6).
     *
     * <p>Predicate: {@code user_id=? AND deactivated_by='loss_event' AND deleted_at IS NULL}
     * (data-model L516 / functional-spec §6.2). The additional {@code deleted_at IS NULL} guard
     * is the tombstone-wins exclusion (functional-spec §10.7 / data-model §5 hand-off note):
     * a reminder the user themselves soft-deleted while {@code ended} MUST stay deleted even
     * though {@code deactivated_by} may still read {@code 'loss_event'} from the prior sweep.
     *
     * <p>Clears the tombstone markers ({@code deactivated_by := NULL},
     * {@code deactivated_at := NULL}), sets {@code active = true}, bumps {@code version}, and
     * stamps {@code updated_at}. Reminders the user disabled BEFORE loss
     * ({@code active=false, deactivated_by=NULL}) are excluded by the
     * {@code deactivated_by='loss_event'} predicate — they stay disabled (functional-spec §10.6).
     *
     * @param userId the caller's own user id (authz scope)
     * @param now    the transaction's server-authoritative instant
     * @return number of reminder rows re-activated by this sweep
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Reminder r SET r.active = true, r.deactivatedBy = NULL, " +
           "r.deactivatedAt = NULL, r.updatedAt = :now, r.version = r.version + 1 " +
           "WHERE r.userId = :userId AND r.deactivatedBy = 'loss_event' " +
           "AND r.deletedAt IS NULL")
    int sweepReactivateOnReopen(@Param("userId") UUID userId, @Param("now") Instant now);

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
