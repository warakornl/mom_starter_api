package com.momstarter.selflog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.auth.EmailVerifiedGuard;
import com.momstarter.error.ApiException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only REST controller for {@code GET /v1/self-logs} (Slice 1 Task 4).
 *
 * <p>History/restore view for the authenticated user's self-logged health metrics.
 * The <strong>primary client read path</strong> is local SQLite + {@code sync/pull};
 * this endpoint exists for restore/verify/new-device-before-sync (spec §A.2 / contract
 * "Self-logs").
 *
 * <h3>Contract invariants</h3>
 * <ul>
 *   <li><strong>Empty = 200</strong> (not 404) — {@code {items:[], nextCursor:null}}.</li>
 *   <li>{@code deleted_at IS NULL} — tombstoned logs excluded.</li>
 *   <li>Optional {@code metricType} filter (invalid value → 400 {@code unknown_metric_type}).</li>
 *   <li>Optional {@code from}/{@code to} on {@code logged_at} (floating-civil, FLAG-1).</li>
 *   <li><strong>Inclusive civil-date bounds (spec §A.2):</strong> the controller normalises
 *       both ends to the full civil day — {@code from} to {@code toLocalDate().atStartOfDay()}
 *       and {@code to} to {@code toLocalDate().atTime(LocalTime.MAX)} — so a row at e.g.
 *       06:00 is included when {@code from=YYYY-MM-DDT08:00} and a row at 23:59:30 is included
 *       when {@code to=YYYY-MM-DDT23:59}. Both ends are symmetric civil-date-inclusive.</li>
 *   <li>Keyset pagination: {@code (loggedAt DESC, id DESC)}, default 100 / max 500 (N5).</li>
 *   <li>Consent gate: <strong>auth + {@code email_verified} only</strong> (ADR G-4).
 *       No {@code cloud_storage} gate — a read of own already-synced rows is not a new egress.</li>
 *   <li>Ownership: {@code userId = JWT.sub()} — never from a query param (IDOR prevention, D7).</li>
 *   <li>Ciphertext: value/note columns returned as opaque Base64 — server NEVER decrypts
 *       (spec §A.2 / ADR Decision 1 / INV-S2).</li>
 * </ul>
 *
 * <h3>Cursor</h3>
 * <p>Opaque Base64-JSON token encoding the keyset position {@code (loggedAt, id)} with a 1h TTL.
 * Expired or tampered cursor → {@code 400 invalid_cursor}. Mirrors
 * {@link com.momstarter.kickcount.KickCountSessionController.HistoryCursor}.
 */
@RestController
@RequestMapping("/self-logs")
class SelfLogController {

    /** Default page size (api-contract N5). */
    private static final int DEFAULT_LIMIT = 100;

    /** Maximum page size (api-contract N5). */
    private static final int MAX_LIMIT = 500;

    /** Cursor TTL in milliseconds (1 hour). */
    private static final long CURSOR_TTL_MS = 3_600_000L;

    /** Floating-civil formatter for loggedAt displayed in the response body (FLAG-1). */
    private static final DateTimeFormatter CIVIL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /**
     * Full-precision ISO formatter for the cursor's {@code loggedAt} field.
     *
     * <p>Using CIVIL_FMT (minute-precision) in the cursor would truncate seconds/nanos and
     * corrupt the keyset window: rows in {@code (truncatedMinute, actualLoggedAt]} would be
     * skipped on continuation. This formatter preserves seconds and nanoseconds so the
     * {@code (loggedAt DESC, id DESC)} keyset is always exact (spec §A.2 point 4).
     * Note: CIVIL_FMT remains for the wire {@code loggedAt} response field — unchanged.
     */
    private static final DateTimeFormatter CURSOR_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Closed enum of valid metricType values.
     * DB CHECK is the enforcement backstop; this validates the GET query param (spec §A.2 / E2).
     * An unknown value → 400 {@code validation_error(unknown_metric_type)}.
     */
    private static final Set<String> VALID_METRIC_TYPES = Set.of(
            "weight", "blood_pressure", "swelling", "lochia", "symptom"
    );

    private final SelfLogRepository repository;
    private final EmailVerifiedGuard emailVerifiedGuard;
    private final ObjectMapper objectMapper;

    SelfLogController(SelfLogRepository repository,
                      EmailVerifiedGuard emailVerifiedGuard,
                      ObjectMapper objectMapper) {
        this.repository = repository;
        this.emailVerifiedGuard = emailVerifiedGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a paged list of live self-logs for the authenticated user.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code metricType} — optional; one of {@code weight|blood_pressure|swelling|lochia|symptom};
     *       invalid value → 400</li>
     *   <li>{@code from} — optional lower bound on {@code loggedAt} ("YYYY-MM-DDTHH:mm");
     *       normalised to start-of-day (inclusive civil-date bound, §A.2)</li>
     *   <li>{@code to}   — optional upper bound on {@code loggedAt} ("YYYY-MM-DDTHH:mm");
     *       normalised to end-of-day (inclusive civil-date bound, §A.2)</li>
     *   <li>{@code cursor} — opaque continuation token (1h TTL; absent = first page)</li>
     *   <li>{@code limit}  — page size [1, 500]; default 100</li>
     * </ul>
     *
     * <p>Response body: {@code {items: [SelfLogResponse…], nextCursor?: string}}.
     *
     * <p>Status codes:
     * <ul>
     *   <li>200 — always for a successful read (including empty result — NOT 404)</li>
     *   <li>400 — {@code validation_error(unknown_metric_type)} or {@code invalid_cursor}</li>
     *   <li>401 — unauthenticated (no Bearer token)</li>
     *   <li>403 — {@code email_unverified} (ADR G-4)</li>
     * </ul>
     */
    @GetMapping
    ResponseEntity<Map<String, Object>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String metricType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        // 1. Auth: email_verified gate (ADR G-4 — evaluated before any data access)
        emailVerifiedGuard.requireVerified(jwt);
        UUID userId = UUID.fromString(jwt.getSubject());

        // 2. Validate metricType (spec §A.2 / E2: invalid value → 400, do not silently ignore)
        String validatedMetricType = null;
        if (metricType != null && !metricType.isBlank()) {
            if (!VALID_METRIC_TYPES.contains(metricType)) {
                throw new ApiException(400, "validation_error", "unknown_metric_type");
            }
            validatedMetricType = metricType;
        }

        // 3. Resolve page size
        int pageSize = (limit != null) ? Math.min(Math.max(limit, 1), MAX_LIMIT) : DEFAULT_LIMIT;

        // 4. Parse range filters — spec §A.2: inclusive civil bounds.
        //    Both 'from' and 'to' are normalised to the full civil day so that the client's
        //    minute-precision value never accidentally excludes rows at the day edges:
        //    - 'from' → start-of-day (atStartOfDay): a row at 06:00 is included even when
        //      the client sends from=2026-07-01T08:00 (same civil day).
        //    - 'to'   → end-of-day (atTime(LocalTime.MAX)): a row at 23:59:30 is included
        //      even when the client sends to=2026-07-01T23:59 (minute precision).
        //    Both normalizations are symmetric and implement the "inclusive civil bounds" rule.
        LocalDateTime fromDt = parseCivil(from);
        if (fromDt != null) {
            // Normalise to start-of-day: spec §A.2 — inclusive civil lower bound.
            fromDt = fromDt.toLocalDate().atStartOfDay();
        }
        LocalDateTime toDt = parseCivil(to);
        if (toDt != null) {
            // Normalise to end-of-day: strip seconds/nanos from the parsed minute-precision
            // civil time and replace with LocalTime.MAX (23:59:59.999999999).
            // This ensures the inclusive civil upper bound covers the entire civil day.
            toDt = toDt.toLocalDate().atTime(LocalTime.MAX);
        }

        // 5. Decode cursor if present
        HistoryCursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            decoded = HistoryCursor.decode(cursor, objectMapper);
        }

        // 6. Fetch pageSize+1 to detect hasMore
        Pageable pageable = Pageable.ofSize(pageSize + 1);
        List<SelfLog> rows;
        if (decoded == null) {
            rows = repository.findForRead(userId, validatedMetricType, fromDt, toDt, pageable);
        } else {
            rows = repository.findForReadAfterCursor(
                    userId, validatedMetricType, fromDt, toDt,
                    decoded.cursorLoggedAt(), decoded.cursorId(), pageable);
        }

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }

        // 7. Build response items (ciphertext as Base64 — server never decrypts, INV-S2)
        List<SelfLogResponse> items = rows.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        // 8. Build next cursor if more pages remain
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            SelfLog last = rows.get(rows.size() - 1);
            nextCursor = HistoryCursor.encode(last.getLoggedAt(), last.getId(), objectMapper);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (nextCursor != null) {
            body.put("nextCursor", nextCursor);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Maps a {@link SelfLog} entity to its wire representation.
     *
     * <p>The four {@code bytea} value/note columns are Base64-encoded for JSON transport.
     * When a column is {@code null} (not populated for this metricType, or crypto-shredded
     * on tombstone), the corresponding response field is {@code null} and omitted by
     * {@code @JsonInclude(NON_NULL)} — see {@link SelfLogResponse}.
     * The server NEVER decrypts these bytes (INV-S2 / G4 / ADR Decision 1).
     */
    private SelfLogResponse toResponse(SelfLog s) {
        return new SelfLogResponse(
                s.getId(),
                s.getMetricType(),
                base64OrNull(s.getValueNumeric()),
                base64OrNull(s.getValueNumericSecondary()),
                base64OrNull(s.getValueText()),
                s.getUnit(),
                s.getLoggedAt() != null ? s.getLoggedAt().format(CIVIL_FMT) : null,
                base64OrNull(s.getNoteCipher()),
                s.getVersion(),
                s.getCreatedAt(),
                s.getUpdatedAt(),
                s.getDeletedAt()
        );
    }

    /** Returns the Base64 encoding of {@code bytes}, or {@code null} if {@code bytes} is null. */
    private static String base64OrNull(byte[] bytes) {
        return bytes != null ? Base64.getEncoder().encodeToString(bytes) : null;
    }

    /**
     * Parses a floating-civil timestamp string ({@code "YYYY-MM-DDTHH:mm"}).
     *
     * @return parsed {@link LocalDateTime}, or {@code null} if the input is null, blank,
     *         or unparseable (lenient — bad range params are treated as absent)
     */
    private static LocalDateTime parseCivil(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s, CIVIL_FMT); }
        catch (DateTimeParseException e) { return null; }
    }

    // =========================================================================
    // History cursor (opaque Base64-JSON, 1h TTL)
    // =========================================================================

    /**
     * Opaque cursor for keyset pagination of {@code GET /self-logs}.
     *
     * <p>Encodes the keyset position {@code (loggedAt, id)} for the descending
     * {@code (loggedAt DESC, id DESC)} order, plus an issue timestamp for the 1h TTL.
     * Serialised as Base64URL-JSON (no HMAC in MVP — impact self-only, all queries are
     * JWT-scoped to the requesting user). Mirrors
     * {@link com.momstarter.kickcount.KickCountSessionController.HistoryCursor}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class HistoryCursor {
        /**
         * Full-precision ISO-8601 loggedAt string (e.g. {@code "2026-07-01T09:00:30"} or
         * {@code "2026-07-01T09:00:30.123456789"}).
         *
         * <p>Stored with full seconds/nanos precision (via {@link SelfLogController#CURSOR_FMT})
         * so the keyset window {@code loggedAt < cursorLoggedAt} is never truncated to the
         * minute boundary. Using CIVIL_FMT here would drop sub-minute precision and skip rows
         * in {@code (truncatedMinute, actualLoggedAt]} on continuation (spec §A.2 point 4).
         */
        public String lat;
        public String lid;    // id as UUID string
        public long issued;   // epoch ms for TTL

        /** Encodes a cursor from the last row's keyset fields using full loggedAt precision. */
        static String encode(LocalDateTime loggedAt, UUID id, ObjectMapper mapper) {
            HistoryCursor c = new HistoryCursor();
            // Use CURSOR_FMT (ISO_LOCAL_DATE_TIME) — NOT CIVIL_FMT — to preserve seconds/nanos
            // so the keyset window on the next page is exact. CIVIL_FMT is only for the
            // wire response loggedAt field (toResponse()), not for the cursor position.
            c.lat = loggedAt != null ? loggedAt.format(CURSOR_FMT) : null;
            c.lid = id != null ? id.toString() : null;
            c.issued = System.currentTimeMillis();
            try {
                byte[] json = mapper.writeValueAsBytes(c);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to encode self-log history cursor", e);
            }
        }

        /**
         * Decodes and validates a cursor.
         *
         * @throws ApiException 400 {@code invalid_cursor} if tampered, malformed, or expired
         */
        static HistoryCursor decode(String encoded, ObjectMapper mapper) {
            try {
                byte[] json = Base64.getUrlDecoder().decode(encoded);
                HistoryCursor c = mapper.readValue(json, HistoryCursor.class);
                if (c.lat == null || c.lid == null) {
                    throw new ApiException(400, "invalid_cursor");
                }
                if (System.currentTimeMillis() > c.issued + CURSOR_TTL_MS) {
                    throw new ApiException(400, "invalid_cursor");
                }
                return c;
            } catch (ApiException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ApiException(400, "invalid_cursor");
            }
        }

        /**
         * Parses the stored full-precision ISO loggedAt back to a {@link LocalDateTime}.
         *
         * <p>Uses the default ISO parser (handles {@code "HH:mm:ss"} and optional
         * {@code ".nnnnnnnnn"} nanoseconds) matching the {@link SelfLogController#CURSOR_FMT}
         * used on encode. A minute-precision string from an older cursor would fail here and
         * surface as {@code 400 invalid_cursor} (acceptable: cursors carry a 1h TTL).
         */
        LocalDateTime cursorLoggedAt() {
            try { return LocalDateTime.parse(lat, CURSOR_FMT); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }

        /** Parses the stored {@code id} string back to a {@link UUID}. */
        UUID cursorId() {
            try { return UUID.fromString(lid); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }
    }
}
