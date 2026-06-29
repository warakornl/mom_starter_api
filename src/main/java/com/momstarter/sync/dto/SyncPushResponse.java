package com.momstarter.sync.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /v1/sync/push} (api-contract "Offline-sync engine" §2/3/5/10).
 *
 * <p>Every pushed record lands in <strong>exactly one</strong> of the three arrays:
 * <ul>
 *   <li>{@code applied[]} — clean applies; the client stamps these server {@code version}/{@code updatedAt}.</li>
 *   <li>{@code conflicts[]} — version-mismatch resolutions; client adopts {@code serverRecord}.</li>
 *   <li>{@code rejected[]} — consent/validation/unknown-collection gate failures; client queues these.</li>
 * </ul>
 *
 * <p>{@code timestamp} is the new watermark covering everything applied in this push.
 */
public record SyncPushResponse(
        Instant timestamp,
        List<Applied> applied,
        List<Conflict> conflicts,
        List<Rejected> rejected
) {}
