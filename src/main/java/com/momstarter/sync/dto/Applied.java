package com.momstarter.sync.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in {@code SyncPushResponse.applied[]}.
 *
 * <p>The server echoes back the authoritative server-assigned {@code version} and {@code updatedAt}
 * for every cleanly-applied record (OQ-SYNC-1 = P-A, RESOLVED). The client MUST stamp its local
 * row from this entry and advance its base version — it MUST NOT assume a mutable push left
 * {@code version} un-bumped (api-contract §2).
 *
 * <p>Every pushed record lands in exactly one of {@code applied[]}, {@code conflicts[]}, or
 * {@code rejected[]} — never multiple.
 */
public record Applied(
        String collection,
        UUID id,
        long version,
        Instant updatedAt
) {}
