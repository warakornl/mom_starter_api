package com.momstarter.consumption;

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
 * Repository for {@link ConsumptionMapping}.
 *
 * <p>Access patterns:
 * <ol>
 *   <li><strong>Push apply path</strong> — {@link #findByUserIdAndIdIn} for batch existence check;
 *       {@link #initVersionToOne} for version:=1 sentinel.</li>
 *   <li><strong>Sync pull</strong> — {@link #findForPull} / {@link #findForPullAfterCursor}
 *       keyset scans ordered by {@code (updated_at ASC, id ASC)}. Includes tombstones.</li>
 *   <li><strong>GET /v1/consumption-mappings</strong> — live rows for the read-only REST view
 *       via {@link #findByUserIdAndDeletedAtIsNull}.</li>
 * </ol>
 *
 * <p>HEALTH-SIDE entity (INV-ASD-9): consent gating is per-row in the sync collection,
 * not per-collection.
 * All queries scope by {@code user_id} (IDOR prevention).
 */
public interface ConsumptionMappingRepository extends JpaRepository<ConsumptionMapping, UUID> {

    // -------------------------------------------------------------------------
    // Push path — version initialisation
    // -------------------------------------------------------------------------

    /**
     * Bumps {@code version} from {@code 0} to {@code 1} for a newly-inserted entity.
     * Called immediately after {@code saveAndFlush()} to satisfy the version:=1 contract.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ConsumptionMapping m SET m.version = 1 WHERE m.id = :id AND m.version = 0")
    void initVersionToOne(@Param("id") UUID id);

    // -------------------------------------------------------------------------
    // Push path — batch id lookup
    // -------------------------------------------------------------------------

    /** Returns mappings owned by {@code userId} whose ids appear in {@code ids}. */
    List<ConsumptionMapping> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // -------------------------------------------------------------------------
    // Pull path — watermark keyset scan
    // -------------------------------------------------------------------------

    /**
     * Steady-state delta pull: mappings with {@code updated_at >= since}, ordered
     * {@code (updated_at ASC, id ASC)}. Includes tombstones.
     * Covered by {@code ix_consumption_mapping__sync_pull (user_id, updated_at, id)}.
     */
    @Query("SELECT m FROM ConsumptionMapping m " +
           "WHERE m.userId = :userId AND m.updatedAt >= :since " +
           "ORDER BY m.updatedAt ASC, m.id ASC")
    List<ConsumptionMapping> findForPull(@Param("userId") UUID userId,
                                         @Param("since") Instant since,
                                         Pageable pageable);

    /**
     * Cold-start cursor-continuation: resumes after {@code (cursorUpdatedAt, cursorId)}.
     */
    @Query("SELECT m FROM ConsumptionMapping m " +
           "WHERE m.userId = :userId AND m.updatedAt >= :since " +
           "AND (m.updatedAt > :cursorUpdatedAt " +
           "     OR (m.updatedAt = :cursorUpdatedAt AND m.id > :cursorId)) " +
           "ORDER BY m.updatedAt ASC, m.id ASC")
    List<ConsumptionMapping> findForPullAfterCursor(@Param("userId") UUID userId,
                                                     @Param("since") Instant since,
                                                     @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                                     @Param("cursorId") UUID cursorId,
                                                     Pageable pageable);

    // -------------------------------------------------------------------------
    // Read-only REST — GET /v1/consumption-mappings
    // -------------------------------------------------------------------------

    /**
     * Returns live (non-deleted) mappings for the given user.
     * Used by the GET endpoint (consent filtering is applied in the controller layer).
     */
    List<ConsumptionMapping> findByUserIdAndDeletedAtIsNull(UUID userId);
}
