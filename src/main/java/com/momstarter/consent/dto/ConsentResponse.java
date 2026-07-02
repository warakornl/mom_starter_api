package com.momstarter.consent.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for a single consent record — returned by {@code POST /account/consents}
 * (201 Created) and as items inside {@link ConsentListResponse} from
 * {@code GET /account/consents}.
 *
 * <p>Fields reflect what the server recorded and are all server-authoritative:
 * {@code id} and {@code grantedAt} are never client-supplied.
 */
public record ConsentResponse(
        UUID id,
        String consentType,
        boolean granted,
        String consentTextVersion,
        Instant grantedAt
) {
}
