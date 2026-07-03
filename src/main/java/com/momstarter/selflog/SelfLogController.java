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
 *   <li><strong>End-of-day normalisation for {@code to}:</strong> the controller normalises
 *       {@code to} to {@code toLocalDate().atTime(LocalTime.MAX)} so a row at e.g. 23:59:30
 *       is included when the caller passes {@code to=YYYY-MM-DDT23:59} (minute precision).
 *       This enforces the inclusive civil-date bucket semantics of spec §A.2.3. The {@code from}
 *       param is used as-is (inclusive lower bound).</li>
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

    /** Floating-civil formatter for loggedAt (FLAG-1). */
    private static final DateTimeFormatter CIVIL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

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
     *   <li>{@code from} — optional lower bound on {@code loggedAt} ("YYYY-MM-DDTHH:mm", inclusive)</li>
     *   <li>{@code to}   — optional upper bound on {@code loggedAt} ("YYYY-MM-DDTHH:mm");
     *       normalised to end-of-day (inclusive civil-date bucket, §A.2.3)</li>
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

        // 4. Parse range filters
        //    from: lower bound — used as-is (inclusive)
        //    to:   upper bound — normalised to end-of-day so a row at e.g. 23:59:30 is included
        //          when the caller sends to=YYYY-MM-DDT23:59 (spec §A.2.3, backend-reviewer
        //          carry-forward: inclusive civil-date bucket semantics).
        LocalDateTime fromDt = parseCivil(from);
        LocalDateTime toDt   = parseCivil(to);
        if (toDt != null) {
            // Normalise to end-of-day: strip seconds/nanos from the parsed minute-precision
            // civil time and replace with LocalTime.MAX (23:59:59.999999999).
            // This ensures the inclusive upper bound covers the entire civil day.
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
        public String lat;    // loggedAt as "YYYY-MM-DDTHH:mm"
        public String lid;    // id as UUID string
        public long issued;   // epoch ms for TTL

        /** Encodes a cursor from the last row's keyset fields. */
        static String encode(LocalDateTime loggedAt, UUID id, ObjectMapper mapper) {
            HistoryCursor c = new HistoryCursor();
            c.lat = loggedAt != null ? loggedAt.format(CIVIL_FMT) : null;
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

        /** Parses the stored {@code loggedAt} string back to a {@link LocalDateTime}. */
        LocalDateTime cursorLoggedAt() {
            try { return LocalDateTime.parse(lat, CIVIL_FMT); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }

        /** Parses the stored {@code id} string back to a {@link UUID}. */
        UUID cursorId() {
            try { return UUID.fromString(lid); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }
    }
}
