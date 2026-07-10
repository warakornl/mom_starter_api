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
 * <p>Excludes {@code version} (internal concurrency token) and raw {@code *_cipher} bytes
 * (those are decrypted before export and represented as readable strings here).
 *
 * <h2>Name fields — DEK-aware decrypt (name-fields-design.md §6)</h2>
 * <p>{@code motherFirstName}, {@code motherLastName}, {@code babyName} hold the
 * <em>decrypted plaintext</em> (not the raw cipher bytes). Under the MVP no-op cipher posture
 * (Option A), the "plaintext" is the UTF-8 bytes the client sent as the field-envelope body.
 * When a cipher column is NULL (never set or crypto-shredded at T0), the corresponding
 * export field is {@code null}.
 *
 * <p>AAD RULING 2b: for row-per-account tables (one profile per account), the AAD
 * {@code recordId} is the {@code accountId} string (not the profile row UUID), because the
 * identity binding is per-account — the row ID is an implementation detail.
 */
public record PregnancyProfileExportEntry(
        UUID id,
        LocalDate edd,
        String eddBasis,
        String lifecycle,
        LocalDate birthDate,
        String deliveryType,
        String birthNote,
        /**
         * Decrypted mother first name, or {@code null} when cipher column is NULL.
         * AAD: collection="pregnancyProfile", field="motherFirstName", recordId=accountId.
         */
        String motherFirstName,
        /**
         * Decrypted mother last name, or {@code null} when cipher column is NULL.
         * AAD: collection="pregnancyProfile", field="motherLastName", recordId=accountId.
         */
        String motherLastName,
        /**
         * Decrypted baby name, or {@code null} when cipher column is NULL.
         * AAD: collection="pregnancyProfile", field="babyName", recordId=accountId.
         */
        String babyName,
        /**
         * Decrypted hospital admission date ({@code YYYY-MM-DD} string), or {@code null}
         * when cipher column is NULL (never set or crypto-shredded at T0).
         * AAD: collection="pregnancyProfile", field="hospitalAdmissionDate", recordId=accountId.
         * The server decrypts and exports the readable date string for PDPA ม.30 completeness.
         * (pregnancy-summary-design.md §1.5 / V20260710000019)
         */
        String hospitalAdmissionDate,
        /**
         * Decrypted hospital discharge date ({@code YYYY-MM-DD} string), or {@code null}
         * when cipher column is NULL.
         * AAD: collection="pregnancyProfile", field="hospitalDischargeDate", recordId=accountId.
         */
        String hospitalDischargeDate,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
