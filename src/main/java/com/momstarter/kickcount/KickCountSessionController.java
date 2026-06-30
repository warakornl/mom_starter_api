package com.momstarter.kickcount;

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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only REST controller for {@code GET /v1/kick-count-sessions}.
 *
 * <p>This is a <strong>history/restore view only</strong> — the primary client read path is
 * local SQLite + {@code sync/pull}. See api-contract A.3 / functional spec §A.3.
 *
 * <h3>Contract invariants</h3>
 * <ul>
 *   <li><strong>Empty = 200</strong> (not 404) — {@code {items:[], nextCursor:null}}.</li>
 *   <li>Only {@code completed} rows are ever served (DB invariant; no explicit status filter needed).</li>
 *   <li>{@code deletedAt IS NULL} — tombstoned sessions are excluded.</li>
 *   <li>Range filter: {@code from}/{@code to} on {@code startedAt} (floating-civil bucket key).</li>
 *   <li>Pagination: {@code cursor}/{@code limit} (default 100, max 500 — N5).</li>
 *   <li>No extra consent gate: auth-only (OQ-K-C resolved; read is not cloud egress).</li>
 *   <li>Ownership: {@code userId = JWT.sub()} on every query (IDOR prevention).</li>
 * </ul>
 *
 * <h3>Cursor</h3>
 * <p>Opaque Base64-JSON token encoding the keyset position {@code (startedAt, id)} with a 1h TTL.
 * Expired or tampered cursor → {@code 400 invalid_cursor}.
 */
@RestController
@RequestMapping("/kick-count-sessions")
class KickCountSessionController {

    /** Default page size (N5). */
    private static final int DEFAULT_LIMIT = 100;

    /** Maximum page size (N5). */
    private static final int MAX_LIMIT = 500;

    /** Cursor TTL in milliseconds (1 hour). */
    private static final long CURSOR_TTL_MS = 3_600_000L;

    /** Floating-civil formatter for startedAt / endedAt (FLAG-1). */
    private static final DateTimeFormatter CIVIL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final KickCountSessionRepository repository;
    private final EmailVerifiedGuard emailVerifiedGuard;
    private final ObjectMapper objectMapper;

    KickCountSessionController(KickCountSessionRepository repository,
                                EmailVerifiedGuard emailVerifiedGuard,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.emailVerifiedGuard = emailVerifiedGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a paged list of completed kick-count sessions for the authenticated user.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code from} — optional lower bound on {@code startedAt} (floating-civil "YYYY-MM-DDTHH:mm")</li>
     *   <li>{@code to}   — optional upper bound on {@code startedAt} (inclusive)</li>
     *   <li>{@code cursor} — opaque continuation token (1h TTL; absent = first page)</li>
     *   <li>{@code limit}  — page size [1, 500]; default 100</li>
     * </ul>
     *
     * <p>Response body: {@code {items: [KickCountSession…], nextCursor?: string}}.
     *
     * <p>Status codes:
     * <ul>
     *   <li>200 — always (including empty result — NOT 404)</li>
     *   <li>400 — {@code invalid_cursor}</li>
     *   <li>401 — unauthenticated</li>
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
        emailVerifiedGuard.requireVerified(jwt);
        UUID userId = UUID.fromString(jwt.getSubject());

        // Resolve page size
        int pageSize = (limit != null) ? Math.min(Math.max(limit, 1), MAX_LIMIT) : DEFAULT_LIMIT;

        // Parse range filters
        LocalDateTime fromDt = parseCivil(from);
        LocalDateTime toDt = parseCivil(to);

        // Decode cursor if present
        HistoryCursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            decoded = HistoryCursor.decode(cursor, objectMapper);
        }

        // Fetch pageSize+1 to detect hasMore
        Pageable pageable = Pageable.ofSize(pageSize + 1);
        List<KickCountSession> rows;
        if (decoded == null) {
            rows = repository.findHistory(userId, fromDt, toDt, pageable);
        } else {
            rows = repository.findHistoryAfterCursor(
                    userId, fromDt, toDt,
                    decoded.cursorStartedAt(), decoded.cursorId(), pageable);
        }

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }

        // Build response items
        List<Map<String, Object>> items = rows.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        // Build next cursor if more pages remain
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            KickCountSession last = rows.get(rows.size() - 1);
            nextCursor = HistoryCursor.encode(last.getStartedAt(), last.getId(), objectMapper);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (nextCursor != null) {
            body.put("nextCursor", nextCursor);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Serialises a {@link KickCountSession} to a response map.
     *
     * <p>note_cipher is echoed as Base64 string under key {@code "note"} (contract field name).
     * When null (no note / tombstoned), the key is omitted.
     * Server NEVER interprets note content (INV-K1 / G4).
     */
    private Map<String, Object> toResponse(KickCountSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("startedAt", s.getStartedAt() != null ? s.getStartedAt().format(CIVIL_FMT) : null);
        m.put("endedAt", s.getEndedAt() != null ? s.getEndedAt().format(CIVIL_FMT) : null);
        m.put("durationSeconds", s.getDurationSeconds());
        m.put("movementCount", s.getMovementCount());
        m.put("targetCount", s.getTargetCount());
        m.put("status", s.getStatus());
        m.put("gestationalWeekAtStart", s.getGestationalWeekAtStart());
        if (s.getNoteCipher() != null) {
            m.put("note", Base64.getEncoder().encodeToString(s.getNoteCipher()));
        }
        m.put("version", s.getVersion());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        m.put("deletedAt", s.getDeletedAt());
        return m;
    }

    private static LocalDateTime parseCivil(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s, CIVIL_FMT); }
        catch (DateTimeParseException e) { return null; }
    }

    // =========================================================================
    // History cursor (opaque Base64-JSON, 1h TTL)
    // =========================================================================

    /**
     * Opaque cursor for keyset pagination of {@code GET /kick-count-sessions}.
     *
     * <p>Encodes keyset position {@code (startedAt, id)} plus an issue timestamp for the
     * 1h TTL. Serialised as Base64URL-JSON (no HMAC in MVP — impact self-only, queries are
     * JWT-scoped to the requesting user; add signing before prod).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class HistoryCursor {
        public String sat;    // startedAt as "YYYY-MM-DDTHH:mm"
        public String sid;    // id as UUID string
        public long issued;   // epoch ms for TTL

        /** Encodes a cursor from the last row's keyset fields. */
        static String encode(LocalDateTime startedAt, UUID id, ObjectMapper mapper) {
            HistoryCursor c = new HistoryCursor();
            c.sat = startedAt != null ? startedAt.format(CIVIL_FMT) : null;
            c.sid = id != null ? id.toString() : null;
            c.issued = System.currentTimeMillis();
            try {
                byte[] json = mapper.writeValueAsBytes(c);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to encode history cursor", e);
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
                if (c.sat == null || c.sid == null) {
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

        LocalDateTime cursorStartedAt() {
            try { return LocalDateTime.parse(sat, CIVIL_FMT); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }

        UUID cursorId() {
            try { return UUID.fromString(sid); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }
    }
}
