package com.momstarter.sync;

import com.momstarter.sync.dto.Applied;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Generic engine contract for a push-accepted, pull-replicated collection.
 *
 * <p>Implementations register themselves with {@link SyncCollectionRegistry} using their
 * {@link #name()} as the key. Adding a new collection = implementing this interface and
 * annotating with {@code @Component} (Spring auto-discovers via the registry's constructor
 * injection of {@code List<SyncCollection>}).
 *
 * <h3>Engine invariants enforced by implementors</h3>
 * <ul>
 *   <li>Server is the only writer of {@code updated_at}/{@code version}.</li>
 *   <li>Routing by {@code id} (upsert), never by bucket name.</li>
 *   <li>Tombstone-wins is unconditional (deletes always apply).</li>
 *   <li>Mutable records always bump {@code version} on apply (no field-level no-op).</li>
 *   <li>User scope is enforced (IDOR: all DB calls include {@code userId}).</li>
 * </ul>
 */
public interface SyncCollection {

    /**
     * Collection name as used in the push/pull {@code changes} map.
     * Example: {@code "supplyItems"}.
     */
    String name();

    /**
     * Per-collection consent type required in addition to the whole-batch {@code cloud_storage}
     * gate. Returns {@code null} for NON-health collections (e.g. {@code supplyItems}, {@code expenses})
     * that are gated by {@code cloud_storage} only.
     *
     * <p>Returns the consent type string (e.g. {@code "general_health"}) for health collections
     * that require an additional per-collection consent gate (api-contract "Consent gating" §A.2).
     */
    String perCollectionConsentType();

    /**
     * Additional per-collection consent types required BEYOND {@link #perCollectionConsentType()}.
     *
     * <p>Use this for dual-consent collections (e.g. {@code feedingSessions} requires BOTH
     * {@code general_health} (primary, via {@link #perCollectionConsentType()}) AND
     * {@code infant_feeding} (additional, via this method)).
     *
     * <p>The default implementation returns an empty list (no additional gates — the common case).
     * Implementations override this only when a collection requires more than one per-collection
     * consent type at push time.
     *
     * <p>ALL returned consent types must be granted for the collection to be processed.
     * A missing type is reported as a {@code consent_required} rejected entry for the entire
     * collection (same as {@link #perCollectionConsentType()}).
     *
     * @return list of additional consent-type strings (empty by default, never null)
     */
    default List<String> additionalCollectionConsentTypes() {
        return List.of();
    }

    /**
     * Pre-load existing entities for a set of ids in one query (batch, avoids N+1).
     * Returns a map of {@code id → entity}; absent entries mean no server-side row for that id.
     *
     * <p>The sync service calls this once per collection per push before processing individual
     * records, so {@code applyUpsert} and {@code applyDelete} receive the pre-loaded entity.
     *
     * <p>All returned entities MUST belong to {@code userId} (IDOR enforcement at query level).
     */
    Map<UUID, Object> loadExisting(UUID userId, Set<UUID> ids);

    /**
     * Apply one upsert record (from {@code created[]} or {@code updated[]}).
     *
     * @param userId   the authenticated user's id (IDOR scope)
     * @param record   the raw JSON map from the push payload (contains {@code id}, {@code version},
     *                 and collection-specific fields)
     * @param existing the pre-loaded server-side entity for this id, or {@code null} if not found
     * @return one of {@link SyncApplyResult.Success}, {@link SyncApplyResult.ConflictResult},
     *         or {@link SyncApplyResult.RejectedResult}
     */
    SyncApplyResult applyUpsert(UUID userId, Map<String, Object> record, Object existing);

    /**
     * Apply one delete (from {@code deleted[]} — tombstone-wins, unconditional).
     *
     * <p>Delete of a never-seen id → inserts a tombstone skeleton so it converges on other
     * devices (OQ-SYNC-10). Delete of an already-tombstoned id is idempotent.
     *
     * @param userId   the authenticated user's id
     * @param id       the record id to tombstone
     * @param existing the pre-loaded server-side entity for this id, or {@code null} if not found
     * @return the applied result (always lands in {@code applied[]}, never {@code conflicts[]})
     */
    Applied applyDelete(UUID userId, UUID id, Object existing);

    /**
     * Find records for pull in keyset order {@code (updated_at ASC, id ASC)}.
     *
     * <p>If {@code cursorUpdatedAt} is null this is the first batch (or steady-state delta);
     * otherwise it is a cursor continuation and the query must use the row-value predicate
     * {@code (updated_at, id) > (cursorUpdatedAt, cursorId)}.
     *
     * <p>Results include both live rows and tombstones so deletions propagate to other devices.
     *
     * @param userId          the authenticated user's id
     * @param since           effective since: watermark minus safeWindow (≥0); {@link Instant#EPOCH}
     *                        for cold start
     * @param cursorUpdatedAt keyset cursor: {@code updated_at} of the last record in the previous
     *                        batch; {@code null} for the first batch of a drain
     * @param cursorId        keyset cursor: {@code id} of the last record; {@code null} for first batch
     * @param pageable        page size (batch limit + 1 to detect hasMore)
     * @return records in {@code (updated_at ASC, id ASC)} order
     */
    List<PullRecord> findForPull(UUID userId, Instant since,
                                  Instant cursorUpdatedAt, UUID cursorId,
                                  Pageable pageable);
}
