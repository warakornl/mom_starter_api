package com.momstarter.account.dto.export;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Pregnancy profile fields exported for PDPA ม.30/31.
 *
 * <p>All fields are health data (PDPA ม.26 sensitive personal data). At-rest encryption
 * is handled at the RDS/KMS layer; this export reflects the plaintext value that the
 * server holds and the user originally supplied.
 *
 * <p>Excludes {@code version} (internal concurrency token).
 */
public record PregnancyProfileExportEntry(
        UUID id,
        LocalDate edd,
        String eddBasis,
        String lifecycle,
        LocalDate birthDate,
        String deliveryType,
        String birthNote,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
