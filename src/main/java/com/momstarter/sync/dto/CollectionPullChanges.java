package com.momstarter.sync.dto;

import java.util.List;
import java.util.UUID;

/**
 * Per-collection change set inside a {@code SyncPullResponse}.
 *
 * <p>Per the contract (OQ-SYNC-17 RESOLVED): the server returns all live rows in the window
 * under {@code updated[]} and tombstones under {@code deleted[]}. {@code created[]} is
 * <strong>always empty on pull</strong> — the client upserts by {@code id}, so the created-vs-updated
 * distinction is irrelevant; {@code rn-mobile-dev} treats pull {@code updated[]} as upsert-by-id.
 */
public record CollectionPullChanges(
        List<Object> created,    // always empty (per OQ-SYNC-17)
        List<Object> updated,    // live rows (upsert-by-id on client)
        List<UUID> deleted       // tombstone IDs
) {}
