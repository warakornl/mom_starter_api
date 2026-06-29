package com.momstarter.sync.dto;

import java.util.UUID;

/**
 * One entry in {@code SyncPushResponse.conflicts[]}.
 *
 * <p>Conflicts arise only from <strong>version-mismatch resolution</strong> (LWW S-A):
 * <ul>
 *   <li>{@code server_won} — base {@code version} &lt; current (someone else wrote since the
 *       client pulled); the push record is discarded, the client adopts {@code serverRecord}.</li>
 *   <li>{@code client_won} — defensive/tie-break path; client write still applied but the
 *       {@code serverRecord} carries the newly-assigned server version (client must stamp it).</li>
 *   <li>{@code tombstone_won} — the server row is tombstoned; the update is discarded and the
 *       client adopts the tombstone.</li>
 * </ul>
 *
 * <p>A conflict is <strong>not</strong> a rejection (consent/validation). Those go in
 * {@code rejected[]} (api-contract §3 / OQ-SYNC-7).
 */
public record Conflict(
        String collection,
        UUID id,
        String resolution,   // "server_won" | "client_won" | "tombstone_won"
        Object serverRecord  // authoritative current server state
) {}
