package com.momstarter.consent;

import com.momstarter.consent.dto.ConsentInput;
import com.momstarter.consent.dto.ConsentListResponse;
import com.momstarter.consent.dto.ConsentResponse;
import com.momstarter.error.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for the consent slice:
 * {@code POST /account/consents} (record a grant or withdrawal) and
 * {@code GET /account/consents} (list the history with cursor pagination).
 *
 * <p>Locale normalisation: the {@code Accept-Language} header is resolved to
 * {@code 'th'} or {@code 'en'} BEFORE the INSERT, per design §3.1.  The DB
 * {@code CHECK (locale IN ('th','en'))} is a last-line-of-defence guard, not
 * the primary normalisation point (so the endpoint never fails with 500 on an
 * unrecognised locale value).
 *
 * <p>Consent-type validation: the set of 6 valid types is enforced here before
 * the INSERT.  An unknown value returns {@code 422 validation_error}.
 */
@Service
public class ConsentService {

    /** The 6 PDPA consent purposes supported by the system (aligned with migration CHECK). */
    static final Set<String> VALID_CONSENT_TYPES = Set.of(
            "general_health",
            "sensitive_lab_results",
            "pdf_egress",
            "infant_feeding",
            "cloud_storage",
            "child_health"
    );

    /** Default page size for {@code GET /account/consents}. */
    private static final int DEFAULT_LIMIT = 20;

    /** Maximum page size for {@code GET /account/consents}. */
    private static final int MAX_LIMIT = 100;

    private final ConsentRecordRepository consentRecordRepository;

    public ConsentService(ConsentRecordRepository consentRecordRepository) {
        this.consentRecordRepository = consentRecordRepository;
    }

    // -------------------------------------------------------------------------
    // POST /account/consents — record a grant or withdrawal
    // -------------------------------------------------------------------------

    /**
     * Records a consent grant or withdrawal event for the authenticated user.
     *
     * <p>Steps:
     * <ol>
     *   <li>Validate {@code consentType} against the known set — 422 if unknown.</li>
     *   <li>Normalise {@code acceptLanguage} header → {@code 'th'} | {@code 'en'}.</li>
     *   <li>INSERT an immutable row (append-only model; no UPDATE/upsert).</li>
     *   <li>Return the server-assigned {@code id} and {@code grantedAt}.</li>
     * </ol>
     *
     * @param userId         authenticated user id (from JWT, never client-supplied)
     * @param input          validated request body
     * @param acceptLanguage raw {@code Accept-Language} header value (may be null)
     * @return the persisted consent record as a response DTO
     * @throws ApiException 422 when {@code consentType} is not one of the 6 known values
     */
    @Transactional
    public ConsentResponse record(UUID userId, ConsentInput input, String acceptLanguage) {
        if (!VALID_CONSENT_TYPES.contains(input.getConsentType())) {
            throw new ApiException(422, "validation_error");
        }

        String locale = normalizeLocale(acceptLanguage);

        ConsentRecord record = new ConsentRecord();
        record.setUserId(userId);
        record.setConsentType(input.getConsentType());
        record.setGranted(input.getGranted());
        record.setConsentTextVersion(input.getConsentTextVersion());
        record.setLocale(locale);
        // grantedAt and createdAt are set by @PrePersist; id is also server-generated there

        ConsentRecord saved = consentRecordRepository.save(record);
        return toResponse(saved);
    }

    // -------------------------------------------------------------------------
    // GET /account/consents — paginated history list
    // -------------------------------------------------------------------------

    /**
     * Returns a cursor-paginated list of consent records for the authenticated user,
     * ordered by {@code (granted_at DESC, id DESC)} (most recent first).
     *
     * <p>Cursor format: URL-safe base64 of {@code "<epochMs>:<uuid>"}.
     * A missing or null cursor returns the first page.  An unparseable cursor
     * returns {@code 400 invalid_cursor}.
     *
     * @param userId authenticated user id
     * @param cursor opaque pagination cursor (null or absent → first page)
     * @param limit  number of items per page; clamped to [1, {@value #MAX_LIMIT}],
     *               defaults to {@value #DEFAULT_LIMIT}
     * @return the page of consent records and the next-page cursor (null if no more pages)
     */
    @Transactional(readOnly = true)
    public ConsentListResponse list(UUID userId, String cursor, Integer limit) {
        int pageSize = resolveLimit(limit);
        int fetchSize = pageSize + 1; // fetch one extra to detect next page

        List<ConsentRecord> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = consentRecordRepository.findByUserIdOrderByGrantedAtDescIdDesc(
                    userId, PageRequest.of(0, fetchSize));
        } else {
            String[] parts = decodeCursor(cursor);
            Instant cursorGrantedAt = Instant.ofEpochMilli(Long.parseLong(parts[0]));
            UUID cursorId = UUID.fromString(parts[1]);
            rows = consentRecordRepository.findByUserIdBeforeCursor(
                    userId, cursorGrantedAt, cursorId, PageRequest.of(0, fetchSize));
        }

        String nextCursor = null;
        List<ConsentRecord> page;
        if (rows.size() > pageSize) {
            page = rows.subList(0, pageSize);
            // Cursor points to the last item in the returned page
            ConsentRecord last = page.get(pageSize - 1);
            nextCursor = encodeCursor(last.getGrantedAt(), last.getId());
        } else {
            page = rows;
        }

        List<ConsentResponse> items = page.stream().map(this::toResponse).toList();
        return new ConsentListResponse(items, nextCursor);
    }

    // -------------------------------------------------------------------------
    // Package-visible helpers (visible for unit testing)
    // -------------------------------------------------------------------------

    /**
     * Normalises an {@code Accept-Language} header value to {@code 'th'} or {@code 'en'}.
     *
     * <p>Logic (per design §3.1):
     * <ul>
     *   <li>Extract the primary language tag (first element before {@code ','} and {@code ';'}).</li>
     *   <li>If the primary tag starts with {@code "en"} (case-insensitive) → {@code 'en'}.</li>
     *   <li>Everything else (including {@code null}, blank, {@code "th"}, {@code "ja"}, etc.)
     *       → {@code 'th'} (default, matching the app's primary market).</li>
     * </ul>
     *
     * @param acceptLanguage raw header value; {@code null} or blank is treated as Thai default
     * @return {@code "th"} or {@code "en"}
     */
    static String normalizeLocale(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return "th";
        }
        // Primary tag = first element before ',' (quality list) and ';' (quality weight)
        String primary = acceptLanguage.split(",")[0].split(";")[0].trim();
        return primary.toLowerCase().startsWith("en") ? "en" : "th";
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static int resolveLimit(Integer requested) {
        if (requested == null || requested < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    private static String encodeCursor(Instant grantedAt, UUID id) {
        String raw = grantedAt.toEpochMilli() + ":" + id.toString();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeCursor(String cursor) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            String raw = new String(bytes, StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 2);
            if (parts.length != 2) {
                throw new ApiException(400, "invalid_cursor");
            }
            // Validate both parts are parseable before returning
            Long.parseLong(parts[0]);   // epoch millis
            UUID.fromString(parts[1]);  // UUID
            return parts;
        } catch (IllegalArgumentException | NumberFormatException e) {
            throw new ApiException(400, "invalid_cursor");
        }
    }

    private ConsentResponse toResponse(ConsentRecord record) {
        return new ConsentResponse(
                record.getId(),
                record.getConsentType(),
                record.isGranted(),
                record.getConsentTextVersion(),
                record.getGrantedAt()
        );
    }
}
