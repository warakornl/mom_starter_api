package com.momstarter.account.dto.export;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Kick-count session fields exported for PDPA ม.30/31 (DEK-aware export, ADR IMPORTANT-5).
 *
 * <h2>note_cipher re-included as decrypted {@code note} (ADR IMPORTANT-5)</h2>
 * <p>Under DEK-aware server-side decrypt (Decision 5), the server unwraps the per-account
 * DEK from {@code account_dek} and applies the Decision-4 version dispatch before export.
 * The decrypted plaintext is emitted as {@link #note}. Raw cipher bytes are NEVER exported.
 *
 * <p>When {@code note_cipher} is {@code null} (tombstoned / crypto-shredded §4.4(A)), or
 * the session was saved without a note, {@link #note} is {@code null} and is omitted from
 * the JSON response via {@code @JsonInclude(NON_NULL)}.
 *
 * <h2>ม.30 completeness</h2>
 * <p>The mother's free-text session note is her own health data. Excluding it from the
 * PDPA data-access export would violate ม.30 completeness (PDPA ruling 5e / ADR IMPORTANT-5).
 * DEK-aware decrypt makes it readable — the historical "useless without DEK" rationale no
 * longer applies once the server unwraps the DEK.
 *
 * <h2>Also excludes</h2>
 * <p>{@code version} and {@code clientId} (internal sync metadata not meaningful to the user).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KickCountSessionExportEntry(
        UUID id,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Integer durationSeconds,
        Integer movementCount,
        int targetCount,
        String status,
        Integer gestationalWeekAtStart,
        /**
         * Decrypted plaintext of {@code kick_count_session.note_cipher}.
         * {@code null} when the session had no note, or the cipher field was crypto-shredded
         * (tombstoned §4.4(A)). Omitted from JSON when null (NON_NULL).
         */
        String note,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
