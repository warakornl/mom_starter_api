package com.momstarter.medication;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.auth.EmailVerifiedGuard;
import com.momstarter.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only REST controller for {@code GET /v1/medication-plans} (Slice 2 Task 4).
 *
 * <p>Provides the authenticated user's live (non-deleted) medication plans in descending
 * keyset order ({@code updated_at DESC, id DESC}). The <strong>primary client read path</strong>
 * is local SQLite + {@code sync/pull}; this endpoint exists for restore/verify/new-device
 * (spec §A.2).
 *
 * <h3>Contract invariants</h3>
 * <ul>
 *   <li><strong>Empty = 200</strong> (not 404) — {@code {items:[], nextCursor absent}}.</li>
 *   <li>{@code deleted_at IS NULL} — tombstoned plans excluded.</li>
 *   <li><strong>NO from/to filter</strong> — plans have no event bucket key; the ordering key
 *       is {@code updated_at} (spec §A.2 / RULING 7.4).</li>
 *   <li>Keyset pagination: {@code (updated_at DESC, id DESC)}, default 100 / max 500.</li>
 *   <li>Consent gate: <strong>auth + {@code email_verified} only</strong> (ADR G-4 RULING 7.4).
 *       No {@code cloud_storage} gate — a read of own already-synced rows is not a new egress.</li>
 *   <li>Ownership: {@code userId = JWT.sub()} — never from a query param (IDOR prevention, D7).</li>
 *   <li>Ciphertext: name/dose columns returned as opaque Base64 — server NEVER decrypts (INV-M3).</li>
 *   <li>scheduleRule: re-parsed from stored JSON string to {@link JsonNode} for wire format.</li>
 * </ul>
 *
 * <h3>Cursor</h3>
 * <p>Opaque Base64-JSON token encoding the keyset position {@code (updatedAt, id)} with a 1h TTL.
 * {@code updatedAt} is an {@link Instant} (UTC), formatted as ISO-8601 with full precision.
 * Expired or tampered cursor → {@code 400 invalid_cursor}.
 */
@RestController
@RequestMapping("/medication-plans")
class MedicationPlanController {

    private static final Logger log = LoggerFactory.getLogger(MedicationPlanController.class);

    /** Default page size (api-contract §A.2). */
    private static final int DEFAULT_LIMIT = 100;

    /** Maximum page size (api-contract §A.2). */
    private static final int MAX_LIMIT = 500;

    /** Cursor TTL in milliseconds (1 hour). */
    private static final long CURSOR_TTL_MS = 3_600_000L;

    private final MedicationPlanRepository repository;
    private final EmailVerifiedGuard emailVerifiedGuard;
    private final ObjectMapper objectMapper;

    MedicationPlanController(MedicationPlanRepository repository,
                             EmailVerifiedGuard emailVerifiedGuard,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.emailVerifiedGuard = emailVerifiedGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a paged list of live medication plans for the authenticated user.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code cursor} — opaque continuation token (1h TTL; absent = first page)</li>
     *   <li>{@code limit}  — page size [1, 500]; default 100</li>
     * </ul>
     *
     * <p>Response body: {@code {items: [MedicationPlanResponse…], nextCursor?: string}}.
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
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        // 1. Auth: email_verified gate (ADR G-4 RULING 7.4 — evaluated before any data access).
        //    No cloud_storage gate: reading own already-synced rows is not a new egress.
        emailVerifiedGuard.requireVerified(jwt);
        UUID userId = UUID.fromString(jwt.getSubject());

        // 2. Resolve page size
        int pageSize = (limit != null) ? Math.min(Math.max(limit, 1), MAX_LIMIT) : DEFAULT_LIMIT;

        // 3. Decode cursor if present
        PlanCursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            decoded = PlanCursor.decode(cursor, objectMapper);
        }

        // 4. Fetch pageSize+1 to detect hasMore
        Pageable pageable = Pageable.ofSize(pageSize + 1);
        List<MedicationPlan> rows;
        if (decoded == null) {
            rows = repository.findForRead(userId, pageable);
        } else {
            rows = repository.findForReadAfterCursor(
                    userId, decoded.cursorUpdatedAt(), decoded.cursorId(), pageable);
        }

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }

        // 5. Build response items (ciphertext as Base64 — server never decrypts, INV-M3)
        List<MedicationPlanResponse> items = rows.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        // 6. Build next cursor if more pages remain
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            MedicationPlan last = rows.get(rows.size() - 1);
            nextCursor = PlanCursor.encode(last.getUpdatedAt(), last.getId(), objectMapper);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (nextCursor != null) {
            body.put("nextCursor", nextCursor);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Maps a {@link MedicationPlan} entity to its wire representation.
     *
     * <p>Ciphertext columns (name/dose) are Base64-encoded for JSON transport.
     * The server NEVER decrypts these bytes (INV-M3 / ADR Decision 1).
     * {@link #scheduleRule} is re-parsed from the stored JSON string to a {@link JsonNode}
     * so it is embedded as a JSON object on the wire (not double-encoded as a string).
     * Internal-only fields (userId, clientId, sourceSuggestionStateId) are NOT included.
     */
    private MedicationPlanResponse toResponse(MedicationPlan p) {
        return new MedicationPlanResponse(
                p.getId(),
                base64OrNull(p.getNameCipher()),
                base64OrNull(p.getDoseCipher()),
                parseScheduleRule(p.getScheduleRule()),
                p.isActive(),
                p.getVersion(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getDeletedAt()
        );
    }

    /** Returns the Base64 encoding of {@code bytes}, or {@code null} if {@code bytes} is null. */
    private static String base64OrNull(byte[] bytes) {
        return bytes != null ? Base64.getEncoder().encodeToString(bytes) : null;
    }

    /**
     * Parses the stored {@code scheduleRule} JSON string to a {@link JsonNode} for wire output.
     *
     * <p>The scheduleRule is stored as a {@code jsonb} column (via {@code @JdbcTypeCode(SqlTypes.JSON)}).
     * Returning it as a raw String would cause Jackson to double-encode it as a string literal.
     * Re-parsing to {@link JsonNode} lets Jackson embed it as a nested JSON object.
     * H2 in PostgreSQL MODE may double-encode to a TextNode wrapper — unwrap once if detected
     * (same pattern as {@code MedicationPlanSyncCollection.toRecord()}).
     */
    private JsonNode parseScheduleRule(String rrJson) {
        if (rrJson == null) return null;
        try {
            JsonNode node = objectMapper.readTree(rrJson);
            if (node.isTextual()) {
                // H2 double-encodes jsonb values as a JSON string literal — unwrap once
                node = objectMapper.readTree(node.textValue());
            }
            return node;
        } catch (Exception ex) {
            log.warn("Failed to parse scheduleRule; returning null for safety. cause={}",
                    ex.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Plan cursor (opaque Base64-JSON, 1h TTL)
    // =========================================================================

    /**
     * Opaque cursor for keyset pagination of {@code GET /medication-plans}.
     *
     * <p>Encodes the keyset position {@code (updatedAt, id)} for the descending
     * {@code (updated_at DESC, id DESC)} order, plus an issue timestamp for the 1h TTL.
     * {@link #uat} carries the full ISO-8601 instant string (e.g. {@code "2026-07-01T09:00:30Z"})
     * — full precision ensures the keyset window on the next page is exact.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class PlanCursor {
        /**
         * Full-precision ISO-8601 Instant string for {@code updatedAt}
         * (e.g. {@code "2026-07-01T09:00:30.123456789Z"}).
         * Precision matters for the keyset window — truncating to seconds would miss rows
         * within the same second on cursor continuation.
         */
        public String uat;
        public String lid;    // id as UUID string
        public long issued;   // epoch ms for TTL

        /** Encodes a cursor from the last row's keyset fields with full updatedAt precision. */
        static String encode(Instant updatedAt, UUID id, ObjectMapper mapper) {
            PlanCursor c = new PlanCursor();
            c.uat = updatedAt != null ? updatedAt.toString() : null; // ISO-8601 with full precision
            c.lid = id != null ? id.toString() : null;
            c.issued = System.currentTimeMillis();
            try {
                byte[] json = mapper.writeValueAsBytes(c);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to encode medication-plan cursor", e);
            }
        }

        /**
         * Decodes and validates a cursor.
         *
         * @throws ApiException 400 {@code invalid_cursor} if tampered, malformed, or expired
         */
        static PlanCursor decode(String encoded, ObjectMapper mapper) {
            try {
                byte[] json = Base64.getUrlDecoder().decode(encoded);
                PlanCursor c = mapper.readValue(json, PlanCursor.class);
                if (c.uat == null || c.lid == null) {
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

        /** Parses the stored ISO Instant string back to an {@link Instant}. */
        Instant cursorUpdatedAt() {
            try { return Instant.parse(uat); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }

        /** Parses the stored {@code id} string back to a {@link UUID}. */
        UUID cursorId() {
            try { return UUID.fromString(lid); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }
    }
}
