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
 * Repository for {@link ReminderOccurrence}.
 *
 * <p>Access patterns mirror {@code SupplyItemRepository} with one additional method
 * for the soft-link adherence-history read (occurrences by reminder id).
 *
 * <p>Key invariants for the sync apply path:
 * <ul>
 *   <li>On {@code sync/push} the server RECOMPUTES the expected occurrence id from
 *       {@code (reminderId, scheduledLocalTime)} and rejects a mismatch (422).
 *       See {@link com.momstarter.occurrence.OccurrenceId#compute}.</li>
 *   <li>M1 status-merge precedence: {@code done}/{@code snoozed} outranks {@code missed}
 *       for the same id — enforced at the apply path, not here.</li>
 *   <li>W-A sparsity: only {@code done}/{@code snoozed} rows exist in production.
 *       {@code due}/{@code missed} are valid enum values but not pushed in MVP.</li>
 * </ul>
 */
public interface ReminderOccurrenceRepository extends JpaRepository<ReminderOccurrence, UUID> {

    // -------------------------------------------------------------------------
    // Push path — version initialisation
    // -------------------------------------------------------------------------

    /**
     * Bumps {@code version} from {@code 0} to {@code 1} for a newly INSERTed occurrence.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ReminderOccurrence o SET o.version = 1 WHERE o.id = :id AND o.version = 0")
    void initVersionToOne(@Param("id") UUID id);

    // -------------------------------------------------------------------------
    // Push path — batch id lookup
    // -------------------------------------------------------------------------

    /**
     * Returns occurrences owned by {@code userId} whose ids appear in {@code ids}.
     * Used by the sync/push apply path for version-arbitrated LWW conflict check.
     */
    List<ReminderOccurrence> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // -------------------------------------------------------------------------
    // Pull path — watermark keyset scan
    // -------------------------------------------------------------------------

    /**
     * Steady-state delta pull: all occurrences for {@code userId} with
     * {@code updated_at >= since}, ordered by {@code (updated_at ASC, id ASC)}.
     * Includes tombstones and all status values (done/snoozed/due/missed) for propagation.
     */
    @Query("SELECT o FROM ReminderOccurrence o WHERE o.userId = :userId AND o.updatedAt >= :since " +
           "ORDER BY o.updatedAt ASC, o.id ASC")
    List<ReminderOccurrence> findForPull(@Param("userId") UUID userId,
                                         @Param("since") Instant since,
                                         Pageable pageable);

    /**
     * Cold-start cursor-continuation keyset scan.
     * Reuses {@code ix_reminder_occurrences__sync_pull (user_id, updated_at, id)}.
     */
    @Query("SELECT o FROM ReminderOccurrence o WHERE o.userId = :userId AND o.updatedAt >= :since " +
           "AND (o.updatedAt > :cursorUpdatedAt " +
           "     OR (o.updatedAt = :cursorUpdatedAt AND o.id > :cursorId)) " +
           "ORDER BY o.updatedAt ASC, o.id ASC")
    List<ReminderOccurrence> findForPullAfterCursor(@Param("userId") UUID userId,
                                                     @Param("since") Instant since,
                                                     @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                                     @Param("cursorId") UUID cursorId,
                                                     Pageable pageable);

    // -------------------------------------------------------------------------
    // Read-only REST — live occurrences list
    // -------------------------------------------------------------------------

    /**
     * Returns live (non-deleted) occurrences for the given user, in keyset order.
     */
    @Query("SELECT o FROM ReminderOccurrence o WHERE o.userId = :userId AND o.deletedAt IS NULL " +
           "ORDER BY o.updatedAt ASC, o.id ASC")
    List<ReminderOccurrence> findByUserIdAndDeletedAtIsNull(@Param("userId") UUID userId,
                                                             Pageable pageable);

    // -------------------------------------------------------------------------
    // Adherence history — occurrences by reminder id (soft link, orphan-tolerant)
    // -------------------------------------------------------------------------

    /**
     * Returns all live occurrences for a given {@code reminderId}, ordered by
     * {@code scheduledLocalTime ASC} (adherence history view for the calendar/PDF).
     *
     * <p>The {@code reminderId} is a soft link; the query tolerates a tombstoned or
     * absent parent {@link Reminder} (OQ-CAL-6 — orphan retention).
     */
    @Query("SELECT o FROM ReminderOccurrence o " +
           "WHERE o.userId = :userId AND o.reminderId = :reminderId AND o.deletedAt IS NULL " +
           "ORDER BY o.scheduledLocalTime ASC")
    List<ReminderOccurrence> findLiveByUserIdAndReminderId(@Param("userId") UUID userId,
                                                            @Param("reminderId") UUID reminderId);

    // -------------------------------------------------------------------------
    // PDPA ม.30/31 — data export (all records, including tombstones)
    // -------------------------------------------------------------------------

    /**
     * Returns ALL reminder occurrences for the given user, including soft-deleted tombstones.
     * Used exclusively by the {@code GET /account/export} path (PDPA ม.30/31 portability).
     *
     * <p>Size note: in MVP, only {@code done}/{@code snoozed} occurrences are pushed to the
     * server (W-A sparse table). The result set is bounded by the user's actual adherence
     * history and is naturally small for a single-user export.
     *
     * @param userId the authenticated user's id (IDOR scope enforced by the service)
     * @return all occurrences in {@code (scheduled_local_time ASC, id ASC)} order
     */
    @Query("SELECT o FROM ReminderOccurrence o WHERE o.userId = :userId " +
           "ORDER BY o.scheduledLocalTime ASC, o.id ASC")
    List<ReminderOccurrence> findAllByUserIdForExport(@Param("userId") UUID userId);
}
