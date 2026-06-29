package com.momstarter.sync;

import java.time.Instant;
import java.util.UUID;

/**
 * A single row returned from a {@link SyncCollection#findForPull} scan.
 *
 * <p>The sync service uses {@code deletedAt} to split results into {@code updated[]} (live)
 * and {@code deleted[]} (tombstones) in the {@code SyncPullResponse} (OQ-SYNC-17).
 * {@code updatedAt} feeds the keyset cursor advancement {@code (updatedAt, id) > (prev)}.
 */
public record PullRecord(
        UUID id,
        Instant updatedAt,
        Instant deletedAt,  // null = live row; non-null = tombstone
        Object data         // serialisable map / DTO for the response body
) {
    public boolean isTombstone() {
        return deletedAt != null;
    }
}
