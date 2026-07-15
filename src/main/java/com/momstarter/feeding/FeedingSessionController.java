package com.momstarter.feeding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.auth.EmailVerifiedGuard;
import com.momstarter.error.ApiException;
import com.momstarter.pregnancy.ConsentChecker;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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
 * Read-only REST controller for {@code GET /v1/feeding-sessions}.
 *
 * <p>History/restore view only — the primary client read path is local SQLite + {@code sync/pull}.
 *
 * <h3>Dual consent gate</h3>
 * <p>Requires BOTH {@code general_health} AND {@code infant_feeding} (mirrors the push gate).
 * Either absent → {@code 403 consent_required}.
 *
 * <h3>Contract invariants</h3>
 * <ul>
 *   <li>Empty = 200 (not 404).</li>
 *   <li>Tombstoned sessions ({@code deletedAt IS NOT NULL}) are excluded.</li>
 *   <li>Range filter: {@code from}/{@code to} on {@code startedAt} (floating-civil bucket key).</li>
 *   <li>Pagination: cursor/{@code limit} (default 100, max 500).</li>
 *   <li>Ownership: {@code userId = JWT.sub()} on every query (IDOR prevention).</li>
 * </ul>
 *
 * <h3>INV-ASD-4 / INV-ASD-8 / INV-ASD-9</h3>
 * <p>Response items carry ZERO supply-side linkage (no {@code supplyItemId}, no {@code fedAt},
 * no {@code usesRemainingInOpenContainer}). Server NEVER parses or aggregates amountSubUnits (G4).
 */
@RestController
@RequestMapping("/feeding-sessions")
class FeedingSessionController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final long CURSOR_TTL_MS = 3_600_000L;
    private static final DateTimeFormatter CIVIL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final FeedingSessionRepository repository;
    private final ConsentChecker consentChecker;
    private final EmailVerifiedGuard emailVerifiedGuard;
    private final ObjectMapper objectMapper;

    FeedingSessionController(FeedingSessionRepository repository,
                              ConsentChecker consentChecker,
                              EmailVerifiedGuard emailVerifiedGuard,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.consentChecker = consentChecker;
        this.emailVerifiedGuard = emailVerifiedGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a paged list of live feeding sessions for the authenticated user.
     *
     * <p>Requires BOTH {@code general_health} AND {@code infant_feeding} consent;
     * missing either → 403.
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

        // Dual consent gate (SD-10: infant_feeding ม.20 + general_health ม.26)
        if (!consentChecker.isGranted(userId, "general_health")) {
            throw new ApiException(403, "consent_required", "general_health");
        }
        if (!consentChecker.isGranted(userId, "infant_feeding")) {
            throw new ApiException(403, "consent_required", "infant_feeding");
        }

        int pageSize = (limit != null) ? Math.min(Math.max(limit, 1), MAX_LIMIT) : DEFAULT_LIMIT;
        LocalDateTime fromDt = parseCivil(from);
        LocalDateTime toDt = parseCivil(to);

        HistoryCursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            decoded = HistoryCursor.decode(cursor, objectMapper);
        }

        Pageable pageable = Pageable.ofSize(pageSize + 1);
        List<FeedingSession> rows;
        if (decoded == null) {
            rows = repository.findHistory(userId, fromDt, toDt, pageable);
        } else {
            rows = repository.findHistoryAfterCursor(
                    userId, fromDt, toDt,
                    decoded.cursorStartedAt(), decoded.cursorId(), pageable);
        }

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = rows.subList(0, pageSize);

        List<Map<String, Object>> items = rows.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            FeedingSession last = rows.get(rows.size() - 1);
            nextCursor = HistoryCursor.encode(last.getStartedAt(), last.getId(), objectMapper);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (nextCursor != null) body.put("nextCursor", nextCursor);
        return ResponseEntity.ok(body);
    }

    /**
     * Serialises a {@link FeedingSession} to a response map.
     *
     * <p>INV-ASD-4/8/9: emits ZERO supply-side linkage.
     * {@code note_cipher} echoed as Base64 under key {@code "note"} (omitted when null).
     * Server NEVER parses amountSubUnits (G4).
     */
    private Map<String, Object> toResponse(FeedingSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("kind", s.getKind());
        m.put("side", s.getSide());
        m.put("startedAt", s.getStartedAt() != null ? s.getStartedAt().format(CIVIL_FMT) : null);
        m.put("durationSeconds", s.getDurationSeconds());
        m.put("volumeMl", s.getVolumeMl());
        m.put("amountSubUnits", s.getAmountSubUnits()); // verbatim; null for non-formula
        if (s.getNoteCipher() != null) {
            m.put("note", Base64.getEncoder().encodeToString(s.getNoteCipher()));
        }
        m.put("version", s.getVersion());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class HistoryCursor {
        public String sat;    // startedAt as "YYYY-MM-DDTHH:mm"
        public String sid;    // id as UUID string
        public long issued;   // epoch ms for TTL

        static String encode(LocalDateTime startedAt, UUID id, ObjectMapper mapper) {
            HistoryCursor c = new HistoryCursor();
            c.sat = startedAt != null ? startedAt.format(CIVIL_FMT) : null;
            c.sid = id != null ? id.toString() : null;
            c.issued = System.currentTimeMillis();
            try {
                byte[] json = mapper.writeValueAsBytes(c);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to encode feeding session cursor", e);
            }
        }

        static HistoryCursor decode(String encoded, ObjectMapper mapper) {
            try {
                byte[] json = Base64.getUrlDecoder().decode(encoded);
                HistoryCursor c = mapper.readValue(json, HistoryCursor.class);
                if (c.sat == null || c.sid == null) throw new ApiException(400, "invalid_cursor");
                if (System.currentTimeMillis() > c.issued + CURSOR_TTL_MS)
                    throw new ApiException(400, "invalid_cursor");
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
