package com.momstarter.sync;

import com.momstarter.sync.dto.Applied;
import com.momstarter.sync.dto.Conflict;
import com.momstarter.sync.dto.Rejected;

/**
 * Sealed result of a single-record apply operation in {@link SyncCollection}.
 *
 * <p>Every pushed record lands in exactly one of the three outcomes (api-contract §2/3):
 * <ul>
 *   <li>{@link Success} → {@code applied[]}</li>
 *   <li>{@link ConflictResult} → {@code conflicts[]}</li>
 *   <li>{@link RejectedResult} → {@code rejected[]}</li>
 * </ul>
 */
public sealed interface SyncApplyResult
        permits SyncApplyResult.Success, SyncApplyResult.ConflictResult, SyncApplyResult.RejectedResult {

    record Success(Applied applied) implements SyncApplyResult {}

    record ConflictResult(Conflict conflict) implements SyncApplyResult {}

    record RejectedResult(Rejected rejected) implements SyncApplyResult {}
}
