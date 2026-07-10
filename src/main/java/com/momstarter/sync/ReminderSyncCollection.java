package com.momstarter.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.reminder.Reminder;
import com.momstarter.reminder.ReminderRepository;
import com.momstarter.sync.dto.Applied;
import com.momstarter.sync.dto.Conflict;
import com.momstarter.sync.dto.Rejected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@link SyncCollection} implementation for the {@code reminders} collection.
 *
 * <h3>Record class</h3>
 * <p>Mutable → LWW on server {@code updated_at} + optimistic {@code version}
 * (identical pattern to {@code supplyItems}).
 *
 * <h3>Per-collection consent gate</h3>
 * <p>{@code reminders} is a MOTHER-health collection (data-model §3.5): gated by
 * {@code general_health} in addition to the whole-batch {@code cloud_storage} gate.
 *
 * <h3>FLAG-4 grammar validation</h3>
 * <p>The {@code recurrenceRule} JSON field is validated on {@code sync/push} (api-contract
 * "Recurrence grammar &amp; deterministic expansion" FLAG-4 §a):
 * <ul>
 *   <li>bad {@code freq} → {@code rejected[] validation_error}</li>
 *   <li>{@code interval &lt; 1} or present when {@code freq ≠ every_n_days}
 *       → {@code rejected[] validation_error}</li>
 *   <li>empty/duplicate/unsorted/non-{@code HH:mm} {@code timesOfDay}
 *       → {@code rejected[] validation_error}</li>
 *   <li>{@code timesOfDay}/{@code interval}/{@code until} on {@code one_off}
 *       → {@code rejected[] validation_error}</li>
 * </ul>
 */
@Component
class ReminderSyncCollection implements SyncCollection {

    private static final Logger log = LoggerFactory.getLogger(ReminderSyncCollection.class);

    private static final String COLLECTION = "reminders";

    /** Valid reminder types per data-model §3.5. */
    private static final Set<String> VALID_TYPES = Set.of(
            "medication", "kick_count", "feeding", "appointment", "supply_restock", "custom");

    /** Valid source_ref_type values. */
    private static final Set<String> VALID_SOURCE_REF_TYPES = Set.of(
            "medication_plan", "checklist_item", "supply_item");

    /** Valid care_activity_type values (V20260710000022 CHECK constraint). */
    private static final Set<String> VALID_CARE_ACTIVITY_TYPES = Set.of("diaper_change", "bathing");

    /** Valid freq values per FLAG-4 grammar §a (extended with "weekly"). */
    private static final Set<String> VALID_FREQS = Set.of("one_off", "daily", "every_n_days", "weekly");

    /**
     * Valid ISO weekday tokens for {@code byDay} (FLAG-4 grammar, weekly extension).
     * Tokens are two-letter uppercase RFC-5545 abbreviations, NOT integers, to prevent
     * the JS getDay(0=Sun) vs Java DayOfWeek(1=Mon) cross-platform integer mismatch
     * that would silently fork the occurrence-id hash and strand done/snoozed rows.
     */
    private static final Set<String> VALID_WEEKDAY_TOKENS =
            Set.of("MO", "TU", "WE", "TH", "FR", "SA", "SU");

    /**
     * Canonical order index for {@code byDay} tokens: 0=MO, 1=TU, ..., 6=SU.
     * Used to enforce ascending canonical order (mirrors the discipline of
     * {@code validateTimesOfDay} for ascending {@code timesOfDay}).
     */
    private static final Map<String, Integer> TOKEN_INDEX = Map.of(
            "MO", 0, "TU", 1, "WE", 2, "TH", 3, "FR", 4, "SA", 5, "SU", 6);

    /** Maximum {@code interval} value for {@code weekly} (OQ-3 decision: 1..52 cap on weekly only). */
    private static final int WEEKLY_MAX_INTERVAL = 52;

    /** HH:mm pattern (hours 00-23, minutes 00-59). */
    private static final Pattern HH_MM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /** Floating-civil minute-precision formatter. */
    private static final DateTimeFormatter CIVIL_MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final ReminderRepository repository;
    private final ObjectMapper objectMapper;

    ReminderSyncCollection(ReminderRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() { return COLLECTION; }

    /** {@code reminders} is a MOTHER-health collection — gated by {@code general_health}. */
    @Override
    public String perCollectionConsentType() { return "general_health"; }

    // -------------------------------------------------------------------------
    // Batch pre-load (push path)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Object> loadExisting(UUID userId, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return repository.findByUserIdAndIdIn(userId, ids)
                .stream()
                .collect(Collectors.toMap(Reminder::getId, r -> (Object) r));
    }

    // -------------------------------------------------------------------------
    // Apply upsert (version-arbitrated LWW S-A)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public SyncApplyResult applyUpsert(UUID userId, Map<String, Object> record, Object existing) {
        UUID id = extractUUID(record, "id");
        if (id == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, null, "validation_error", "id is required"));
        }

        // Validate recurrenceRule grammar (FLAG-4 §a).
        // The wire contract (api-contract §ReminderInput) requires recurrenceRule to be a
        // nested JSON object, not a string.  When Jackson deserialises the push body the
        // field arrives as a LinkedHashMap; we serialise it back to a canonical JSON string
        // before grammar validation so that validateRecurrenceRule() always operates on
        // well-formed JSON.  A pre-serialised String value is accepted as a safety net.
        Object recurrenceRuleRaw = record.get("recurrenceRule");
        String recurrenceRuleJson;
        if (recurrenceRuleRaw == null) {
            recurrenceRuleJson = null;
        } else if (recurrenceRuleRaw instanceof String s) {
            recurrenceRuleJson = s;
        } else {
            try {
                recurrenceRuleJson = objectMapper.writeValueAsString(recurrenceRuleRaw);
            } catch (Exception e) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "validation_error",
                                "recurrenceRule is not serializable"));
            }
        }
        String ruleError = validateRecurrenceRule(recurrenceRuleJson);
        if (ruleError != null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", ruleError));
        }

        // Validate required fields
        String displayTitle = extractString(record, "displayTitle");
        String type = extractString(record, "type");
        String startAtStr = extractString(record, "startAt");

        if (displayTitle == null || displayTitle.isBlank()) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "displayTitle is required"));
        }
        if (type == null || !VALID_TYPES.contains(type)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error",
                            "type must be one of: " + String.join(", ", VALID_TYPES)));
        }
        LocalDateTime startAt = parseCivilMinute(startAtStr);
        if (startAt == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error",
                            "startAt must be YYYY-MM-DDTHH:mm"));
        }

        long baseVersion = extractBaseVersion(record);
        Reminder current = (Reminder) existing;

        if (current == null) {
            return insertNew(userId, id, record, displayTitle, type, recurrenceRuleJson, startAt);
        }

        if (current.getDeletedAt() != null) {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "tombstone_won", toRecord(current)));
        }

        long currentVersion = current.getVersion() != null ? current.getVersion() : 0L;
        if (baseVersion == currentVersion) {
            return updateExisting(current, userId, id, record, displayTitle, type, recurrenceRuleJson, startAt);
        } else {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", toRecord(current)));
        }
    }

    private SyncApplyResult insertNew(UUID userId, UUID id, Map<String, Object> record,
                                       String displayTitle, String type,
                                       String recurrenceRuleJson, LocalDateTime startAt) {
        Reminder r = new Reminder();
        r.setId(id);
        r.setUserId(userId);
        applyFields(r, record, displayTitle, type, recurrenceRuleJson, startAt);
        try {
            r = repository.saveAndFlush(r);
            repository.initVersionToOne(r.getId());
            return new SyncApplyResult.Success(new Applied(COLLECTION, r.getId(), 1L, r.getUpdatedAt()));
        } catch (Exception ex) {
            Reminder reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", null));
        }
    }

    private SyncApplyResult updateExisting(Reminder current, UUID userId, UUID id,
                                            Map<String, Object> record, String displayTitle,
                                            String type, String recurrenceRuleJson,
                                            LocalDateTime startAt) {
        applyFields(current, record, displayTitle, type, recurrenceRuleJson, startAt);
        try {
            current = repository.saveAndFlush(current);
            return new SyncApplyResult.Success(new Applied(COLLECTION, current.getId(),
                    current.getVersion() != null ? current.getVersion() : 0L,
                    current.getUpdatedAt()));
        } catch (OptimisticLockingFailureException ex) {
            Reminder reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            throw ex;
        }
    }

    // -------------------------------------------------------------------------
    // Apply delete (tombstone-wins)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public Applied applyDelete(UUID userId, UUID id, Object existing) {
        Reminder item = (Reminder) existing;

        if (item == null) {
            // Never-seen id → insert tombstone skeleton (OQ-SYNC-10)
            Reminder skeleton = new Reminder();
            skeleton.setId(id);
            skeleton.setUserId(userId);
            skeleton.setType("custom");
            skeleton.setDisplayTitle("");
            skeleton.setRecurrenceRule("{\"freq\":\"one_off\"}");
            skeleton.setStartAt(LocalDateTime.now());
            skeleton.setDeletedAt(Instant.now());
            skeleton = repository.saveAndFlush(skeleton);
            repository.initVersionToOne(skeleton.getId());
            return new Applied(COLLECTION, id, 1L, skeleton.getUpdatedAt());
        }

        if (item.getDeletedAt() != null) {
            return new Applied(COLLECTION, id,
                    item.getVersion() != null ? item.getVersion() : 0L,
                    item.getUpdatedAt());
        }

        item.setDeletedAt(Instant.now());
        item = repository.saveAndFlush(item);
        return new Applied(COLLECTION, id,
                item.getVersion() != null ? item.getVersion() : 0L,
                item.getUpdatedAt());
    }

    // -------------------------------------------------------------------------
    // Pull
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<PullRecord> findForPull(UUID userId, Instant since,
                                         Instant cursorUpdatedAt, UUID cursorId,
                                         Pageable pageable) {
        List<Reminder> rows = (cursorUpdatedAt == null)
                ? repository.findForPull(userId, since, pageable)
                : repository.findForPullAfterCursor(userId, since, cursorUpdatedAt, cursorId, pageable);
        return rows.stream()
                .map(r -> new PullRecord(r.getId(), r.getUpdatedAt(), r.getDeletedAt(), toRecord(r)))
                .toList();
    }

    // -------------------------------------------------------------------------
    // FLAG-4 grammar validation (§a)
    // -------------------------------------------------------------------------

    /**
     * Validates {@code recurrenceRule} JSON against the FLAG-4 grammar
     * (api-contract FLAG-4 §a, extended with the {@code weekly}/{@code byDay} grammar).
     *
     * <p>New rules added by the weekly extension (recurrence-weekly-byday-design §3):
     * <ul>
     *   <li>{@code "weekly"} added to the valid {@code freq} set.</li>
     *   <li>{@code byDay} is <strong>FORBIDDEN</strong> on {@code one_off} / {@code daily} /
     *       {@code every_n_days} → {@code validation_error}.</li>
     *   <li>{@code weekly} requires a non-empty {@code byDay} in canonical order
     *       ({@code MO<TU<WE<TH<FR<SA<SU}), no duplicates, all valid ISO tokens.</li>
     *   <li>Each {@code byDay} element is {@code instanceof String}-checked before token
     *       comparison to prevent {@code ClassCastException} on malformed jsonb payloads
     *       (e.g. {@code [123]}) — mirror of the HH_MM guard in {@code validateTimesOfDay}.</li>
     *   <li>{@code interval} cap {@code 1..52} on {@code weekly} ONLY (OQ-3 decision).
     *       The cap is intentionally <strong>not</strong> retrofitted onto {@code every_n_days}
     *       (backward-incompatible — would 422 already-stored valid rules on re-sync).</li>
     * </ul>
     *
     * @return null if valid; a human-readable error string if invalid
     */
    String validateRecurrenceRule(String json) {
        if (json == null || json.isBlank()) {
            return "recurrenceRule is required";
        }
        Map<String, Object> rule;
        try {
            rule = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return "recurrenceRule is not valid JSON";
        }

        String freq = extractStringFromMap(rule, "freq");
        if (freq == null || !VALID_FREQS.contains(freq)) {
            return "freq must be one of: one_off, daily, every_n_days, weekly";
        }

        Object byDayRaw = rule.get("byDay");
        Object intervalRaw = rule.get("interval");
        Object timesOfDayRaw = rule.get("timesOfDay");
        Object untilRaw = rule.get("until");

        switch (freq) {
            case "one_off" -> {
                // byDay, timesOfDay, interval, until are FORBIDDEN on one_off (FLAG-4 §a)
                if (byDayRaw != null) {
                    return "byDay is forbidden for one_off";
                }
                if (timesOfDayRaw != null) {
                    return "timesOfDay is forbidden for one_off";
                }
                if (intervalRaw != null) {
                    return "interval is forbidden for one_off";
                }
                // until being null is fine; being present-and-non-null is forbidden
                if (untilRaw != null) {
                    return "until is forbidden for one_off";
                }
            }
            case "daily", "every_n_days" -> {
                // byDay is FORBIDDEN on daily and every_n_days
                if (byDayRaw != null) {
                    return "byDay is forbidden for " + freq;
                }
                // timesOfDay required, non-empty, sorted, no-dups, each "HH:mm"
                String timesError = validateTimesOfDay(timesOfDayRaw);
                if (timesError != null) return timesError;

                if ("every_n_days".equals(freq)) {
                    if (intervalRaw == null) {
                        return "interval is required for every_n_days";
                    }
                    int interval;
                    try { interval = ((Number) intervalRaw).intValue(); }
                    catch (Exception e) { return "interval must be an integer"; }
                    if (interval < 1) {
                        return "interval must be >= 1";
                    }
                    // NOTE: no upper-bound cap on every_n_days (OQ-3: backward-compat risk)
                } else {
                    // daily: interval must be absent (or 1 treated as absent)
                    if (intervalRaw != null) {
                        int interval;
                        try { interval = ((Number) intervalRaw).intValue(); }
                        catch (Exception e) { return "interval must be an integer"; }
                        if (interval != 1) {
                            return "interval is forbidden for daily (or must be 1)";
                        }
                    }
                }
                // until: if present and non-null, must be YYYY-MM-DD
                if (untilRaw != null) {
                    try { LocalDate.parse(untilRaw.toString()); }
                    catch (DateTimeParseException e) {
                        return "until must be YYYY-MM-DD or null";
                    }
                }
            }
            case "weekly" -> {
                // byDay: required, non-empty, each element a valid String token,
                // no duplicates, canonical order MO<TU<WE<TH<FR<SA<SU
                String byDayError = validateByDay(byDayRaw);
                if (byDayError != null) return byDayError;

                // timesOfDay: required, non-empty, canonical (same rules as daily/every_n_days)
                String timesError = validateTimesOfDay(timesOfDayRaw);
                if (timesError != null) return timesError;

                // interval: optional; if present must be integer 1..52 (OQ-3 weekly-only cap)
                if (intervalRaw != null) {
                    int weeklyInterval;
                    try { weeklyInterval = ((Number) intervalRaw).intValue(); }
                    catch (Exception e) { return "interval must be an integer"; }
                    if (weeklyInterval < 1) {
                        return "interval must be >= 1 for weekly";
                    }
                    if (weeklyInterval > WEEKLY_MAX_INTERVAL) {
                        return "interval must be <= " + WEEKLY_MAX_INTERVAL + " for weekly";
                    }
                }

                // until: optional, YYYY-MM-DD if present
                if (untilRaw != null) {
                    try { LocalDate.parse(untilRaw.toString()); }
                    catch (DateTimeParseException e) {
                        return "until must be YYYY-MM-DD or null";
                    }
                }
            }
        }
        return null; // valid
    }

    /**
     * Validates the {@code byDay} field for a {@code weekly} recurrence rule.
     *
     * <p>Rules (recurrence-weekly-byday-design §3):
     * <ul>
     *   <li>Must be a non-empty List.</li>
     *   <li>Each element must be a {@code String} (instanceof check — prevents
     *       {@code ClassCastException} on malformed payloads like {@code [123]}).</li>
     *   <li>Each element must be one of the 7 ISO tokens {@code MO..SU}.</li>
     *   <li>No duplicates.</li>
     *   <li>Strictly ascending canonical order {@code MO<TU<WE<TH<FR<SA<SU}.</li>
     * </ul>
     *
     * @return null if valid; a human-readable error string if invalid
     */
    private String validateByDay(Object byDayRaw) {
        if (!(byDayRaw instanceof List)) {
            return "byDay is required and must be a non-empty array for weekly";
        }
        @SuppressWarnings("unchecked")
        List<Object> byDayList = (List<Object>) byDayRaw;
        if (byDayList.isEmpty()) {
            return "byDay is required and must be a non-empty array for weekly";
        }
        int prevIdx = -1;
        for (Object elem : byDayList) {
            // instanceof String guard: prevents ClassCastException on malformed jsonb [123]-style payloads
            if (!(elem instanceof String token)) {
                return "byDay entries must be strings";
            }
            if (!VALID_WEEKDAY_TOKENS.contains(token)) {
                return "byDay entries must be one of MO,TU,WE,TH,FR,SA,SU";
            }
            int idx = TOKEN_INDEX.get(token);
            if (idx == prevIdx) {
                return "byDay must not contain duplicates";
            }
            if (idx < prevIdx) {
                return "byDay must be in canonical weekday order (MO<TU<WE<TH<FR<SA<SU)";
            }
            prevIdx = idx;
        }
        return null; // valid
    }

    private String validateTimesOfDay(Object timesOfDayRaw) {
        if (!(timesOfDayRaw instanceof List)) {
            return "timesOfDay is required and must be a non-empty array";
        }
        @SuppressWarnings("unchecked")
        List<Object> times = (List<Object>) timesOfDayRaw;
        if (times.isEmpty()) {
            return "timesOfDay must not be empty";
        }
        String prev = null;
        for (Object t : times) {
            String ts = t != null ? t.toString() : null;
            if (ts == null || !HH_MM.matcher(ts).matches()) {
                return "timesOfDay entries must match HH:mm (24-hour, zero-padded)";
            }
            if (prev != null) {
                if (ts.equals(prev)) {
                    return "timesOfDay must not contain duplicates";
                }
                if (ts.compareTo(prev) < 0) {
                    return "timesOfDay must be in ascending order";
                }
            }
            prev = ts;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Field mapping
    // -------------------------------------------------------------------------

    private void applyFields(Reminder r, Map<String, Object> record, String displayTitle,
                              String type, String recurrenceRuleJson, LocalDateTime startAt) {
        r.setDisplayTitle(displayTitle);
        r.setType(type);
        r.setRecurrenceRule(recurrenceRuleJson);
        r.setStartAt(startAt);

        String sourceRefType = extractString(record, "sourceRefType");
        if (sourceRefType != null && VALID_SOURCE_REF_TYPES.contains(sourceRefType)) {
            r.setSourceRefType(sourceRefType);
            UUID sourceRefId = extractUUID(record, "sourceRefId");
            r.setSourceRefId(sourceRefId);
        } else {
            r.setSourceRefType(null);
            r.setSourceRefId(null);
        }

        Object activeRaw = record.get("active");
        r.setActive(activeRaw == null || Boolean.TRUE.equals(activeRaw)
                || "true".equalsIgnoreCase(String.valueOf(activeRaw)));

        // careActivityType — nullable enum: diaper_change | bathing (ASD §1.1 / V20260710000022)
        // NOTE: 'feeding_formula' is intentionally absent (formula uses FeedingSession, not Reminder)
        String careActivityType = extractString(record, "careActivityType");
        if (careActivityType != null && VALID_CARE_ACTIVITY_TYPES.contains(careActivityType)) {
            r.setCareActivityType(careActivityType);
        } else {
            r.setCareActivityType(null); // unknown/absent → null (safe default)
        }

        String clientIdStr = extractString(record, "clientId");
        if (clientIdStr != null) {
            try { r.setClientId(UUID.fromString(clientIdStr)); } catch (Exception ignored) {}
        }
    }

    Map<String, Object> toRecord(Reminder r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("userId", r.getUserId());
        m.put("type", r.getType());
        m.put("displayTitle", r.getDisplayTitle());
        m.put("sourceRefType", r.getSourceRefType());
        m.put("sourceRefId", r.getSourceRefId());
        // Deserialise the stored JSON string to a JsonNode so Jackson re-serialises it as a
        // nested JSON object on the wire — matching the api-contract ReminderInput shape.
        //
        // H2 2.x in PostgreSQL MODE returns jsonb column values as a JSON-encoded string literal
        // (outer double-quotes + backslash-escaped inner content) rather than the raw object text
        // that PostgreSQL returns.  We therefore parse with readTree() first: if the result is an
        // ObjectNode we use it directly (production / raw-text path); if it is a TextNode
        // (H2 double-encoding path) we re-parse its text value to recover the actual object.
        //
        // Fallback: if both parse attempts fail (should not happen given push-path validation)
        // we emit the raw string rather than throw a 500.
        try {
            String rrJson = r.getRecurrenceRule();
            if (rrJson == null) {
                m.put("recurrenceRule", null);
            } else {
                JsonNode node = objectMapper.readTree(rrJson);
                if (node.isTextual()) {
                    // H2 double-encoded the jsonb value as a JSON string; unwrap once.
                    node = objectMapper.readTree(node.textValue());
                }
                m.put("recurrenceRule", node);
            }
        } catch (Exception ex) {
            // Should not happen: push-path grammar validation ensures only valid JSON reaches
            // this field.  If it does (e.g. out-of-band DB write), emit the raw string so
            // the client at least gets the data rather than a 500, but log loudly so we notice.
            log.warn("toRecord: failed to parse recurrenceRule for reminder id={}; "
                    + "falling back to raw string. cause={}", r.getId(), ex.getMessage());
            m.put("recurrenceRule", r.getRecurrenceRule()); // last-resort raw string fallback
        }
        m.put("startAt", r.getStartAt() != null ? r.getStartAt().format(CIVIL_MINUTE_FMT) : null);
        m.put("active", r.isActive());
        // careActivityType: nullable enum; only emit when non-null (ASD §1.1 / V20260710000022)
        m.put("careActivityType", r.getCareActivityType());
        m.put("clientId", r.getClientId());
        m.put("version", r.getVersion() != null ? r.getVersion() : 0L);
        m.put("createdAt", r.getCreatedAt());
        m.put("updatedAt", r.getUpdatedAt());
        m.put("deletedAt", r.getDeletedAt());
        return m;
    }

    // -------------------------------------------------------------------------
    // Field-extraction utilities
    // -------------------------------------------------------------------------

    private static UUID extractUUID(Map<String, Object> record, String key) {
        Object val = record.get(key);
        if (val == null) return null;
        try { return UUID.fromString(val.toString()); } catch (Exception e) { return null; }
    }

    private static String extractString(Map<String, Object> record, String key) {
        Object val = record.get(key);
        return val != null ? val.toString() : null;
    }

    private static String extractStringFromMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private static long extractBaseVersion(Map<String, Object> record) {
        Object val = record.get("version");
        if (val == null) return 0L;
        try { return ((Number) val).longValue(); } catch (Exception e) { return 0L; }
    }

    /**
     * Parses a floating-civil minute-precision string {@code "YYYY-MM-DDTHH:mm"}.
     * Returns null if the string is null, blank, or cannot be parsed.
     */
    private static LocalDateTime parseCivilMinute(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s, CIVIL_MINUTE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
