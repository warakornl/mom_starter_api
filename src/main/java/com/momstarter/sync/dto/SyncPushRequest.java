package com.momstarter.sync.dto;

import jakarta.validation.constraints.NotNull;

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
 *   <li>email_verified</li>
 *   <li>cloud_storage consent</li>
 *   <li>Batch cap (1000 records / 5 MB)</li>
 * </ol>
 */
public record SyncPushRequest(
        @NotNull
        Map<String, CollectionChanges> changes,

        String lastPulledAt
) {}
