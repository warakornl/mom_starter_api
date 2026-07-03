package com.momstarter.medication;

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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only REST controller for {@code GET /v1/medication-logs} (Slice 2 Task 4).
 *
 * <p>Provides the authenticated user's live (non-deleted) medication logs filtered by an optional
 * civil date range on {@code occurrence_time}, paginated by a keyset cursor.
 *
 * <h3>Contract invariants</h3>
 * <ul>
 *   <li><strong>Empty = 200</strong> (not 404) — {@code {items:[], nextCursor absent}}.</li>
 *   <li>{@code deleted_at IS NULL} — tombstoned logs excluded.</li>
 *   <li>Optional {@code from}/{@code to} on {@code occurrence_time} (floating-civil, FLAG-1 / D5).</li>
 *   <li><strong>Inclusive civil-date bounds (spec §A.3):</strong> the controller normalises
 *       both ends to the full civil day — {@code from} to {@code atStartOfDay()} and
 *       {@code to} to {@code atTime(LocalTime.MAX)} — so a row at e.g. 06:00 is included
 *       when {@code from=YYYY-MM-DDT08:00} (same civil day) and a row at 23:59:30 is
 *       included when {@code to=YYYY-MM-DDT23:59} (minute precision). Both ends are
 *       symmetric civil-date-inclusive. Mirrors {@code SelfLogController} exactly.</li>
 *   <li>Keyset pagination: {@code (occurrenceTime DESC, id DESC)}, default 100 / max 500.</li>
 *   <li><strong>Full-precision keyset cursor</strong>: {@code occurrenceTime} is encoded
 *       using {@code ISO_LOCAL_DATE_TIME} (not minute-truncated {@code CIVIL_FMT}) so the
 *       keyset window on the next page is exact. Minute-truncated cursors would skip rows
 *       within the same minute on continuation. Mirrors {@code SelfLogController}.</li>
 *   <li>Consent gate: <strong>auth + {@code email_verified} only</strong> (ADR G-4 RULING 7.4).
 *       No {@code cloud_storage} gate — a read of own already-synced rows is not a new egress.</li>
 *   <li>Ownership: {@code userId = JWT.sub()} — never from a query param (IDOR prevention, D7).</li>
 *   <li>Plan-agnostic: returns all logs for the user regardless of plan linkage (spec §A.3).</li>
 *   <li>Ciphertext: note column returned as opaque Base64 — server NEVER decrypts (INV-M3).</li>
 * </ul>
 *
 * <h3>Cursor</h3>
 * <p>Opaque Base64-JSON token encoding the keyset position {@code (occurrenceTime, id)} with
 * a 1h TTL. Uses {@code ISO_LOCAL_DATE_TIME} for {@code occurrenceTime} (full precision with
 * seconds and optional nanoseconds). Expired or tampered cursor → {@code 400 invalid_cursor}.
 * Mirrors {@link com.momstarter.selflog.SelfLogController.HistoryCursor} exactly.
 */
@RestController
@RequestMapping("/medication-logs")
class MedicationLogController {

    /** Default page size (api-contract §A.3). */
    private static final int DEFAULT_LIMIT = 100;

    /** Maximum page size (api-contract §A.3). */
    private static final int MAX_LIMIT = 500;

    /** Cursor TTL in milliseconds (1 hour). */
    private static final long CURSOR_TTL_MS = 3_600_000L;

    /**
     * Floating-civil formatter for {@code occurrenceTime} displayed in the response body (FLAG-1).
     * Minute-precision: {@code "YYYY-MM-DDTHH:mm"}.
     */
    private static final DateTimeFormatter CIVIL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /**
     * Full-precision ISO formatter for the cursor's {@code occurrenceTime} field.
     *
     * <p>Using {@link #CIVIL_FMT} (minute-precision) in the cursor would truncate seconds/nanos
     * and corrupt the keyset window: rows in {@code (truncatedMinute, actualOccurrenceTime]}
     * would be skipped on continuation. This formatter preserves seconds and nanoseconds so
     * the {@code (occurrenceTime DESC, id DESC)} keyset is always exact (spec §A.3).
     * {@link #CIVIL_FMT} is used only for the wire {@code occurrenceTime} response field.
     * Mirrors {@code SelfLogController.CURSOR_FMT}.
     */
    private static final DateTimeFormatter CURSOR_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final MedicationLogRepository repository;
    private final EmailVerifiedGuard emailVerifiedGuard;
    private final ObjectMapper objectMapper;

    MedicationLogController(MedicationLogRepository repository,
                            EmailVerifiedGuard emailVerifiedGuard,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.emailVerifiedGuard = emailVerifiedGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a paged list of live medication logs for the authenticated user.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code from} — optional lower bound on {@code occurrenceTime} ("YYYY-MM-DDTHH:mm");
     *       normalised to start-of-day (inclusive civil-date bound, §A.3)</li>
     *   <li>{@code to}   — optional upper bound on {@code occurrenceTime} ("YYYY-MM-DDTHH:mm");
     *       normalised to end-of-day (inclusive civil-date bound, §A.3)</li>
     *   <li>{@code cursor} — opaque continuation token (1h TTL; absent = first page)</li>
     *   <li>{@code limit}  — page size [1, 500]; default 100</li>
     * </ul>
     *
     * <p>Response body: {@code {items: [MedicationLogResponse…], nextCursor?: string}}.
     *
     * <p>Status codes:
     * <ul>
     *   <li>200 — always for a successful read (including empty result — NOT 404)</li>
     *   <li>400 — {@code invalid_cursor}</li>
     *   <li>401 — unauthenticated (no Bearer token)</li>
     *   <li>403 — {@code email_unverified} (ADR G-4 RULING 7.4)</li>
     * </ul>
     */
    @GetMapping
    ResponseEntity<Map<String, Object>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        // 1. Auth: email_verified gate (ADR G-4 RULING 7.4 — evaluated before any data access).
        //    No cloud_storage gate: reading own already-synced rows is not a new egress.
        emailVerifiedGuard.requireVerified(jwt);
        UUID userId = UUID.fromString(jwt.getSubject());

        // 2. Resolve page size
        int pageSize = (limit != null) ? Math.min(Math.max(limit, 1), MAX_LIMIT) : DEFAULT_LIMIT;

        // 3. Parse range filters — spec §A.3: inclusive civil bounds.
        //    Both 'from' and 'to' are normalised to the full civil day so that the client's
        //    minute-precision value never accidentally excludes rows at the day edges:
        //    - 'from' → start-of-day (atStartOfDay): a row at 06:00 is included even when
        //      the client sends from=2026-07-01T08:00 (same civil day).
        //    - 'to'   → end-of-day (atTime(LocalTime.MAX)): a row at 23:59:30 is included
        //      even when the client sends to=2026-07-01T23:59 (minute precision).
        //    Both normalisations are symmetric and implement the "inclusive civil bounds" rule.
        //    Mirrors SelfLogController exactly.
        LocalDateTime fromDt = parseCivil(from);
        if (fromDt != null) {
            // Normalise to start-of-day: spec §A.3 — inclusive civil lower bound.
            fromDt = fromDt.toLocalDate().atStartOfDay();
        }
        LocalDateTime toDt = parseCivil(to);
        if (toDt != null) {
            // Normalise to end-of-day: LocalTime.MAX = 23:59:59.999999999.
            // This ensures the inclusive civil upper bound covers the entire civil day.
            toDt = toDt.toLocalDate().atTime(LocalTime.MAX);
        }

        // 4. Decode cursor if present
        LogCursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            decoded = LogCursor.decode(cursor, objectMapper);
        }

        // 5. Fetch pageSize+1 to detect hasMore
        Pageable pageable = Pageable.ofSize(pageSize + 1);
        List<MedicationLog> rows;
        if (decoded == null) {
            rows = repository.findForRead(userId, fromDt, toDt, pageable);
        } else {
            rows = repository.findForReadAfterCursor(
                    userId, fromDt, toDt,
                    decoded.cursorOccurrenceTime(), decoded.cursorId(), pageable);
        }

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }

        // 6. Build response items (ciphertext as Base64 — server never decrypts, INV-M3)
        List<MedicationLogResponse> items = rows.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        // 7. Build next cursor if more pages remain
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            MedicationLog last = rows.get(rows.size() - 1);
            nextCursor = LogCursor.encode(last.getOccurrenceTime(), last.getId(), objectMapper);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (nextCursor != null) {
            body.put("nextCursor", nextCursor);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Maps a {@link MedicationLog} entity to its wire representation.
     *
     * <p>The {@code note_cipher} column is Base64-encoded for JSON transport.
     * The server NEVER decrypts this field (INV-M3 / ADR Decision 1).
     * {@link MedicationLogResponse#occurrenceTime} is formatted with minute-precision civil format
     * (FLAG-1). {@link MedicationLogResponse#loggedAt} is an absolute-UTC Instant.
     * Internal-only fields (userId, clientId) are NOT included.
     */
    private MedicationLogResponse toResponse(MedicationLog l) {
        return new MedicationLogResponse(
                l.getId(),
                l.getMedicationPlanId(),
                l.getOccurrenceTime() != null ? l.getOccurrenceTime().format(CIVIL_FMT) : null,
                l.getStatus(),
                l.getLoggedAt(),
                base64OrNull(l.getNoteCipher()),
                l.getVersion(),
                l.getCreatedAt(),
                l.getUpdatedAt(),
                l.getDeletedAt()
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
    // Log cursor (opaque Base64-JSON, 1h TTL)
    // =========================================================================

    /**
     * Opaque cursor for keyset pagination of {@code GET /medication-logs}.
     *
     * <p>Encodes the keyset position {@code (occurrenceTime, id)} for the descending
     * {@code (occurrenceTime DESC, id DESC)} order, plus an issue timestamp for the 1h TTL.
     *
     * <p>{@link #oat} carries the full-precision ISO {@code occurrenceTime} string (e.g.
     * {@code "2026-07-01T09:00:30"} or {@code "2026-07-01T09:00:30.123456789"}) — NOT the
     * minute-precision {@link #CIVIL_FMT} used for the wire response field. Using
     * {@code CIVIL_FMT} here would truncate seconds/nanos and skip rows in
     * {@code (truncatedMinute, actualOccurrenceTime]} on continuation (spec §A.3 point 4).
     * Mirrors {@code SelfLogController.HistoryCursor} exactly.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class LogCursor {
        /**
         * Full-precision ISO-8601 occurrenceTime string (e.g. {@code "2026-07-01T09:00:30"}).
         * Encoded with {@link MedicationLogController#CURSOR_FMT} (ISO_LOCAL_DATE_TIME) —
         * NOT with {@link MedicationLogController#CIVIL_FMT} (minute-precision, response only).
         */
        public String oat;
        public String lid;    // id as UUID string
        public long issued;   // epoch ms for TTL

        /** Encodes a cursor from the last row's keyset fields using full occurrenceTime precision. */
        static String encode(LocalDateTime occurrenceTime, UUID id, ObjectMapper mapper) {
            LogCursor c = new LogCursor();
            // Use CURSOR_FMT (ISO_LOCAL_DATE_TIME) to preserve seconds/nanos — NOT CIVIL_FMT
            c.oat = occurrenceTime != null ? occurrenceTime.format(CURSOR_FMT) : null;
            c.lid = id != null ? id.toString() : null;
            c.issued = System.currentTimeMillis();
            try {
                byte[] json = mapper.writeValueAsBytes(c);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to encode medication-log cursor", e);
            }
        }

        /**
         * Decodes and validates a cursor.
         *
         * @throws ApiException 400 {@code invalid_cursor} if tampered, malformed, or expired
         */
        static LogCursor decode(String encoded, ObjectMapper mapper) {
            try {
                byte[] json = Base64.getUrlDecoder().decode(encoded);
                LogCursor c = mapper.readValue(json, LogCursor.class);
                if (c.oat == null || c.lid == null) {
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
         * Parses the stored full-precision ISO occurrenceTime back to a {@link LocalDateTime}.
         *
         * <p>Uses the default ISO parser matching {@link MedicationLogController#CURSOR_FMT}.
         * A minute-precision string from an older cursor would fail here and surface as
         * {@code 400 invalid_cursor} (acceptable: cursors carry a 1h TTL).
         */
        LocalDateTime cursorOccurrenceTime() {
            try { return LocalDateTime.parse(oat, CURSOR_FMT); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }

        /** Parses the stored {@code id} string back to a {@link UUID}. */
        UUID cursorId() {
            try { return UUID.fromString(lid); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }
    }
}
