package com.momstarter.sync.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Response body for {@code GET /v1/sync/pull} (api-contract "Offline-sync engine" §9 / §B.3/B.4).
 *
 * <p>{@code timestamp} = the snapshot-start instant {@code W1}:
 * <ul>
 *   <li>On a steady-state delta pull (no cursor): {@code W1 = now()} at request time; the client
 *       adopts it immediately.</li>
 *   <li>On a cold-start / cursor drain: {@code W1} is stamped ONCE at the first cursor-less
 *       request and returned <strong>identically on every batch</strong>. The client adopts it
 *       ONLY on the final batch ({@code nextCursor} absent). See OQ-SYNC-12 / contract §9 —
 *       using end-of-drain {@code now()} would permanently lose mid-drain writes.</li>
 * </ul>
 *
 * <p>{@code nextCursor} is present only while a cold-start drain is incomplete (partial batch).
 * Absent means this is the final (or only) batch.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncPullResponse(
        Map<String, CollectionPullChanges> changes,
        Instant timestamp,
        String nextCursor   // null on the final or only batch
) {}
