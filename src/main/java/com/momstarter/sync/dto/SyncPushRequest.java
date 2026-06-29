package com.momstarter.sync.dto;

import java.util.Map;

/**
 * Request body for {@code POST /v1/sync/push} (api-contract "Offline-sync engine" §A.1).
 *
 * <p>{@code changes} is a map of collection name → three-bucket change set.
 * {@code lastPulledAt} is the watermark the client last adopted (REQUIRED; "0" for zero/cold-start).
 *
 * <p>Whole-call checks before any record is applied:
 * <ol>
 *   <li>Auth (JWT Bearer)</li>
 *   <li>email_verified (SyncController gate)</li>
 *   <li>cloud_storage consent</li>
 *   <li>Batch cap (1000 records / 5 MB)</li>
 * </ol>
 *
 * <p>Both {@code changes} and {@code lastPulledAt} are structurally required fields; missing
 * either is a {@code 400} (not a Bean Validation {@code 422}).  {@code changes} uses {@code null}
 * (no {@code @NotNull}) so that the service can return the correct {@code 400} error code rather
 * than the framework's default {@code 422} (api-contract §8).
 */
public record SyncPushRequest(
        Map<String, CollectionChanges> changes,  // null-checked in SyncService → 400

        String lastPulledAt
) {}
