package com.momstarter.sync.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Three-bucket push payload for one collection (api-contract "Offline-sync engine" §1 / OQ-SYNC-2).
 *
 * <p>{@code created} and {@code updated} are client-side hints only — the server routes by {@code id}
 * (idempotent upsert). {@code deleted} carries bare UUIDs (no base version — tombstone-wins is
 * unconditional, OQ-SYNC-2 / §A.5).
 */
public record CollectionChanges(
        List<Map<String, Object>> created,
        List<Map<String, Object>> updated,
        List<String> deleted  // bare UUID strings (no base version)
) {
    public CollectionChanges {
        created = created != null ? created : List.of();
        updated = updated != null ? updated : List.of();
        deleted = deleted != null ? deleted : List.of();
    }

    /** Total record count: created + updated + number of deleted IDs. */
    public int totalCount() {
        return created.size() + updated.size() + deleted.size();
    }
}
