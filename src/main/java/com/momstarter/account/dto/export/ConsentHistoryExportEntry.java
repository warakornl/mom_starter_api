package com.momstarter.account.dto.export;

import java.time.Instant;
import java.util.UUID;

/**
 * Consent audit record fields exported for PDPA ม.30/31.
 *
 * <p>The full append-only history is exported (all grant and withdrawal rows), ordered
 * by {@code grantedAt ASC} so the chronological audit trail is readable.
 *
 * <p>All fields are included — consent records are audit data and are inherently
 * meaningful to the user (they evidence every consent decision they made).
 */
public record ConsentHistoryExportEntry(
        UUID id,
        String consentType,
        boolean granted,
        String consentTextVersion,
        String locale,
        Instant grantedAt,
        Instant createdAt
) {
}
