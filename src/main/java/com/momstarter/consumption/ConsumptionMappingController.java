package com.momstarter.consumption;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.auth.EmailVerifiedGuard;
import com.momstarter.error.ApiException;
import com.momstarter.pregnancy.ConsentChecker;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only REST controller for {@code GET /v1/consumption-mappings}.
 *
 * <p>History/restore view — the primary client read path is local SQLite + {@code sync/pull}.
 *
 * <h3>Consent gating (per-activityType, mirrors push gate)</h3>
 * <ul>
 *   <li>{@code general_health} is ALWAYS required.</li>
 *   <li>If {@code infant_feeding} is NOT granted, {@code feeding_formula} rows are EXCLUDED
 *       from the response (filtered server-side). The remaining rows ({@code diaper_change},
 *       {@code bathing}) are returned normally.</li>
 *   <li>If {@code general_health} is absent → {@code 403 consent_required}.</li>
 * </ul>
 *
 * <h3>Contract invariants</h3>
 * <ul>
 *   <li>Empty = 200 (not 404).</li>
 *   <li>Tombstoned rows ({@code deletedAt IS NOT NULL}) are excluded.</li>
 *   <li>Pagination: cursor/{@code limit} (default 100, max 500).</li>
 *   <li>Ownership: {@code userId = JWT.sub()} on every query (IDOR prevention).</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/consumption-mappings")
class ConsumptionMappingController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final long CURSOR_TTL_MS = 3_600_000L;

    /** feeding_formula activityType requires dual consent (infant_feeding + general_health). */
    private static final String FORMULA_ACTIVITY_TYPE = "feeding_formula";

    /**
     * Closed set of valid {@code activityType} values (mirrors DB CHECK constraint).
     * An unrecognised value in the GET query param → {@code 400 validation_error(unknown_activity_type)}.
     */
    private static final Set<String> VALID_ACTIVITY_TYPES = Set.of(
            "feeding_formula", "diaper_change", "bathing"
    );

    private final ConsumptionMappingRepository repository;
    private final ConsentChecker consentChecker;
    private final EmailVerifiedGuard emailVerifiedGuard;
    private final ObjectMapper objectMapper;

    ConsumptionMappingController(ConsumptionMappingRepository repository,
                                  ConsentChecker consentChecker,
                                  EmailVerifiedGuard emailVerifiedGuard,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.consentChecker = consentChecker;
        this.emailVerifiedGuard = emailVerifiedGuard;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    ResponseEntity<Map<String, Object>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String activityType,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        emailVerifiedGuard.requireVerified(jwt);
        UUID userId = UUID.fromString(jwt.getSubject());

        // Validate activityType query param (contract §GET /consumption-mappings):
        // must be one of the allowed enum values or absent; unknown value → 400
        final String validatedActivityType;
        if (activityType != null && !activityType.isBlank()) {
            if (!VALID_ACTIVITY_TYPES.contains(activityType)) {
                throw new ApiException(400, "validation_error", "unknown_activity_type");
            }
            validatedActivityType = activityType;
        } else {
            validatedActivityType = null;
        }

        // general_health is required for all rows
        if (!consentChecker.isGranted(userId, "general_health")) {
            throw new ApiException(403, "consent_required", "general_health");
        }

        // infant_feeding grant determines whether feeding_formula rows are included
        boolean infantFeedingGranted = consentChecker.isGranted(userId, "infant_feeding");

        int pageSize = (limit != null) ? Math.min(Math.max(limit, 1), MAX_LIMIT) : DEFAULT_LIMIT;

        // Decode cursor if present
        MappingCursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            decoded = MappingCursor.decode(cursor, objectMapper);
        }

        // Fetch all live rows; apply cursor pagination by filtering on (updatedAt, id)
        // For MVP cardinality (few dozen mappings per user), fetching all then filtering
        // in Java is acceptable. The pageSize+1 trick for hasMore detection is applied below.
        List<ConsumptionMapping> allLive = repository.findByUserIdAndDeletedAtIsNull(userId);

        // Apply cursor filter (keyset: skip rows at or before cursor position)
        final MappingCursor cursorFinal = decoded;
        if (cursorFinal != null) {
            allLive = allLive.stream()
                    .filter(m -> {
                        Instant mUpdatedAt = m.getUpdatedAt();
                        UUID mId = m.getId();
                        int cmp = mUpdatedAt.compareTo(cursorFinal.cursorUpdatedAt());
                        return cmp > 0 || (cmp == 0 && mId.compareTo(cursorFinal.cursorId()) > 0);
                    })
                    .collect(Collectors.toList());
        }

        // Sort by (updatedAt ASC, id ASC) — mirrors sync-pull keyset order
        allLive.sort((a, b) -> {
            int cmp = a.getUpdatedAt().compareTo(b.getUpdatedAt());
            return cmp != 0 ? cmp : a.getId().compareTo(b.getId());
        });

        // Filter by consent: exclude feeding_formula rows if infant_feeding absent
        if (!infantFeedingGranted) {
            allLive = allLive.stream()
                    .filter(m -> !FORMULA_ACTIVITY_TYPE.equals(m.getActivityType()))
                    .collect(Collectors.toList());
        }

        // Filter by activityType query param (ADDITIONAL, applied after consent filtering)
        if (validatedActivityType != null) {
            allLive = allLive.stream()
                    .filter(m -> validatedActivityType.equals(m.getActivityType()))
                    .collect(Collectors.toList());
        }

        // Apply page size limit (pageSize+1 trick for hasMore)
        boolean hasMore = allLive.size() > pageSize;
        if (hasMore) allLive = allLive.subList(0, pageSize);

        List<Map<String, Object>> items = allLive.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        String nextCursor = null;
        if (hasMore && !allLive.isEmpty()) {
            ConsumptionMapping last = allLive.get(allLive.size() - 1);
            nextCursor = MappingCursor.encode(last.getUpdatedAt(), last.getId(), objectMapper);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (nextCursor != null) body.put("nextCursor", nextCursor);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> toResponse(ConsumptionMapping m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.getId());
        r.put("activityType", m.getActivityType());
        r.put("supplyItemId", m.getSupplyItemId());
        r.put("defaultQty", m.getDefaultQty());
        r.put("enabled", m.isEnabled());
        r.put("version", m.getVersion());
        r.put("createdAt", m.getCreatedAt());
        r.put("updatedAt", m.getUpdatedAt());
        return r;
    }

    // =========================================================================
    // Cursor (opaque Base64-JSON, 1h TTL)
    // =========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class MappingCursor {
        public String uat;  // updatedAt as ISO-8601 string
        public String sid;  // id as UUID string
        public long issued; // epoch ms for TTL

        static String encode(Instant updatedAt, UUID id, ObjectMapper mapper) {
            MappingCursor c = new MappingCursor();
            c.uat = updatedAt != null ? updatedAt.toString() : null;
            c.sid = id != null ? id.toString() : null;
            c.issued = System.currentTimeMillis();
            try {
                byte[] json = mapper.writeValueAsBytes(c);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to encode consumption mapping cursor", e);
            }
        }

        static MappingCursor decode(String encoded, ObjectMapper mapper) {
            try {
                byte[] json = Base64.getUrlDecoder().decode(encoded);
                MappingCursor c = mapper.readValue(json, MappingCursor.class);
                if (c.uat == null || c.sid == null) throw new ApiException(400, "invalid_cursor");
                if (System.currentTimeMillis() > c.issued + CURSOR_TTL_MS)
                    throw new ApiException(400, "invalid_cursor");
                return c;
            } catch (ApiException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ApiException(400, "invalid_cursor");
            }
        }

        Instant cursorUpdatedAt() {
            try { return Instant.parse(uat); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }

        UUID cursorId() {
            try { return UUID.fromString(sid); }
            catch (Exception e) { throw new ApiException(400, "invalid_cursor"); }
        }
    }
}
