package com.momstarter.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for GET /account and PATCH /account (api-contract N9 "Account & sync").
 *
 * <p>The {@code <sync>}-style fields ({@code version}, {@code createdAt}, {@code updatedAt},
 * {@code deletedAt}) serve optimistic concurrency (If-Match on PATCH) and PDPA soft-delete
 * only — Account is NOT a member of {@code SyncChangeSet} (neither push-accepted nor
 * pull-replicated). See "Account & sync (N9)" in api-contract.md.
 *
 * <p>NEVER includes {@code passwordHash} or any internal credential material (data minimisation,
 * PDPA / api-contract §"Conventions").
 *
 * <p>{@code @JsonInclude(NON_NULL)} omits {@code deletedAt} from the JSON response when it is
 * {@code null} (the normal state for a live account).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountResponse(
        UUID id,
        String email,
        String locale,
        String status,
        boolean emailVerified,
        Instant createdAt,
        Instant updatedAt,
        long version,
        Instant deletedAt
) {}
