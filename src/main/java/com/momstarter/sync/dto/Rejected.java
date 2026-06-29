package com.momstarter.sync.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * One entry in {@code SyncPushResponse.rejected[]}.
 *
 * <p>Rejections are <strong>distinct from conflicts</strong>: a conflict is a version-mismatch
 * resolution; a rejection is a gate failure (consent/validation/unknown collection).
 * The rest of the batch still applies when a rejection occurs (api-contract §3 / OQ-SYNC-7).
 *
 * <p>{@code code} values:
 * <ul>
 *   <li>{@code consent_required} — per-collection consent absent; {@code id} is absent (whole
 *       collection); {@code details} = the missing consent type.</li>
 *   <li>{@code validation_error} — per-record field violation; {@code id} = the offending record.</li>
 *   <li>{@code unknown_collection} — the key is not a push-accepted collection; {@code id} absent.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Rejected(
        String collection,
        UUID id,         // null for whole-collection rejections (consent_required, unknown_collection)
        String code,
        String details   // null when not applicable
) {}
