package com.momstarter.checklist;

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
 * Repository for {@link ChecklistItem}.
 *
 * <p>Access patterns mirror {@code SupplyItemRepository} (sync engine + read-only REST):
 * <ol>
 *   <li><strong>{@code POST /sync/push} (apply path)</strong> — {@link #findByUserIdAndIdIn}
 *       for version-arbitrated LWW conflict check.</li>
 *   <li><strong>{@code GET /sync/pull} (pull path)</strong> — safe-window keyset delta
 *       ({@link #findForPull}) and cold-start cursor continuation
 *       ({@link #findForPullAfterCursor}).</li>
 *   <li><strong>{@code GET /checklist-items} (read-only REST)</strong> — live items only
 *       ({@link #findByUserIdAndDeletedAtIsNull}).</li>
 * </ol>
 *
 * <p>The {@code checklistItems} collection is gated by {@code general_health} (per-collection)
 * on {@code sync/push} (MOTHER-health). {@code appointment}/{@code anc_visit} category rules
 * (dated required) are CLIENT-ONLY; the server stores {@code scheduledAt} verbatim.
 */
public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, UUID> {

    // -------------------------------------------------------------------------
    // Push path — version initialisation
    // -------------------------------------------------------------------------

    /**
     * Bumps {@code version} from {@code 0} to {@code 1} for a newly INSERTed entity.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChecklistItem c SET c.version = 1 WHERE c.id = :id AND c.version = 0")
    void initVersionToOne(@Param("id") UUID id);

    // -------------------------------------------------------------------------
    // Push path — batch id lookup
    // -------------------------------------------------------------------------

    /**
     * Returns checklist items owned by {@code userId} whose ids appear in {@code ids}.
     * Used by the sync/push apply path for version-arbitrated LWW conflict check.
     */
    List<ChecklistItem> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // -------------------------------------------------------------------------
    // Pull path — watermark keyset scan
    // -------------------------------------------------------------------------

    /**
     * Steady-state delta pull: all checklist items for {@code userId} with
     * {@code updated_at >= since}, ordered by {@code (updated_at ASC, id ASC)}.
     * Includes tombstones so deletions propagate to other devices.
     */
    @Query("SELECT c FROM ChecklistItem c WHERE c.userId = :userId AND c.updatedAt >= :since " +
           "ORDER BY c.updatedAt ASC, c.id ASC")
    List<ChecklistItem> findForPull(@Param("userId") UUID userId,
                                    @Param("since") Instant since,
                                    Pageable pageable);

    /**
     * Cold-start cursor-continuation keyset scan.
     * Reuses {@code ix_checklist_items__sync_pull (user_id, updated_at, id)}.
     */
    @Query("SELECT c FROM ChecklistItem c WHERE c.userId = :userId AND c.updatedAt >= :since " +
           "AND (c.updatedAt > :cursorUpdatedAt " +
           "     OR (c.updatedAt = :cursorUpdatedAt AND c.id > :cursorId)) " +
           "ORDER BY c.updatedAt ASC, c.id ASC")
    List<ChecklistItem> findForPullAfterCursor(@Param("userId") UUID userId,
                                               @Param("since") Instant since,
                                               @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                               @Param("cursorId") UUID cursorId,
                                               Pageable pageable);

    // -------------------------------------------------------------------------
    // Read-only REST — GET /checklist-items live list
    // -------------------------------------------------------------------------

    /**
     * Returns live (non-deleted) checklist items for the given user, in keyset order.
     * Used by {@code GET /checklist-items} (read-only REST surface).
     */
    @Query("SELECT c FROM ChecklistItem c WHERE c.userId = :userId AND c.deletedAt IS NULL " +
           "ORDER BY c.updatedAt ASC, c.id ASC")
    List<ChecklistItem> findByUserIdAndDeletedAtIsNull(@Param("userId") UUID userId,
                                                        Pageable pageable);

    // -------------------------------------------------------------------------
    // Calendar view — upcoming appointments
    // -------------------------------------------------------------------------

    /**
     * Returns upcoming open appointments and ANC visits (OQ-CAL-8 — PINNED).
     * Next-appointments feed: {@code category IN (appointment, anc_visit)}, not done,
     * {@code scheduled_at >= since}, ascending by {@code scheduled_at}.
     *
     * <p>Pure client read policy — no contract surface; the server-side equivalent is
     * provided here for the PDF egress path and restore verification.
     */
    @Query("SELECT c FROM ChecklistItem c " +
           "WHERE c.userId = :userId " +
           "  AND c.category IN ('appointment', 'anc_visit') " +
           "  AND c.done = false " +
           "  AND c.deletedAt IS NULL " +
           "  AND c.scheduledAt >= :since " +
           "ORDER BY c.scheduledAt ASC")
    List<ChecklistItem> findUpcomingAppointments(@Param("userId") UUID userId,
                                                  @Param("since") java.time.LocalDateTime since);

    // -------------------------------------------------------------------------
    // PDPA ม.30/31 — data export (all records, including tombstones)
    // -------------------------------------------------------------------------

    /**
     * Returns ALL checklist items for the given user, including soft-deleted tombstones.
     * Used exclusively by the {@code GET /account/export} path (PDPA ม.30/31 portability).
     *
     * @param userId the authenticated user's id (IDOR scope enforced by the service)
     * @return all live and tombstoned items in {@code (updated_at ASC, id ASC)} order
     */
    @Query("SELECT c FROM ChecklistItem c WHERE c.userId = :userId " +
           "ORDER BY c.updatedAt ASC, c.id ASC")
    List<ChecklistItem> findAllByUserIdForExport(@Param("userId") UUID userId);
}
