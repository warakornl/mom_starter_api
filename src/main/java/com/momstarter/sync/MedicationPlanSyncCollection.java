package com.momstarter.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.medication.MedicationPlan;
import com.momstarter.medication.MedicationPlanRepository;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@link SyncCollection} implementation for the {@code medicationPlans} collection.
 *
 * <h3>Record class: MUTABLE-LWW (spec D2 / RULING 5)</h3>
 * <p>Mirrors {@link ExpenseSyncCollection} — version-arbitrated LWW: insert new / update
 * existing by version match; {@code server_won} on stale base version; tombstone-wins.
 *
 * <h3>Crypto-shred on tombstone (§4.4(A) / RULING 1)</h3>
 * <p>On soft-delete, {@code name_cipher} and {@code dose_cipher} are set to {@code null}
 * (crypto-shred), per the PDPA ruling. The {@code ck_medication_plan__live_name} DB CHECK
 * ({@code deleted_at IS NOT NULL OR name_cipher IS NOT NULL}) allows NULL name only on
 * tombstones — a live upsert with null name is a server-side {@code name_required} reject.
 *
 * <h3>Per-collection consent gate (D6)</h3>
 * <p>MOTHER-health collection (SD-2) → gated by {@code general_health}. Absent consent
 * yields {@code rejected[]{collection:"medicationPlans", code:"consent_required",
 * details:"general_health"}} with id omitted; rest of batch continues (contract :349).
 *
 * <h3>Validation — closed sub-code enum (RULING 3)</h3>
 * <p>{@code name_required} · {@code name_too_large} · {@code dose_too_large} ·
 * {@code schedule_rule_invalid}
 *
 * <h3>scheduleRule — FLAG-4 grammar reuse (RULING 7.1)</h3>
 * <p>Validates the FLAG-4 recurrence grammar with the medication-specific adaptations:
 * <ul>
 *   <li>Valid {@code freq}: {@code one_off | daily | every_n_days} (NOT {@code weekly}).</li>
 *   <li>{@code startAt} (floating-civil {@code "YYYY-MM-DDTHH:mm"}) is REQUIRED in the rule
 *       (folded in because {@code medication_plan} has no separate {@code startAt} column).</li>
 *   <li>Closed grammar: {@code freq, startAt, interval, timesOfDay, until} only — any
 *       unknown key (e.g. {@code byDay}) → {@code schedule_rule_invalid}.</li>
 *   <li>{@code every_n_days} requires {@code interval >= 2}; {@code interval = 1} is REJECTED
 *       (canonicalise-to-daily is a client concern — RULING 7.1). This is the sole
 *       medication-specific addition over the shared FLAG-4 validator.</li>
 *   <li>{@code null} {@code scheduleRule} is LEGAL — PRN/ad-hoc plan ({@code M = 0} for
 *       adherence, RULING 7.2). NOT treated as malformed.</li>
 * </ul>
 */
@Component
class MedicationPlanSyncCollection implements SyncCollection {

    private static final Logger log = LoggerFactory.getLogger(MedicationPlanSyncCollection.class);

    private static final String COLLECTION = "medicationPlans";

    /** Maximum decoded byte length for name_cipher and dose_cipher (same cap as note_cipher family). */
    private static final int MAX_CIPHER_BYTES = 8192;

    /**
     * Valid freq values for medication schedule_rule (FLAG-4 grammar subset — RULING 7.1).
     * NOTE: {@code "weekly"} is intentionally excluded; medication schedules use only
     * the three civil-recurrence types.
     */
    private static final Set<String> VALID_SCHEDULE_FREQS = Set.of("one_off", "daily", "every_n_days");

    /** Closed grammar key set for medication schedule_rule (RULING 7.1 — unknown keys rejected). */
    private static final Set<String> SCHEDULE_RULE_KNOWN_KEYS =
            Set.of("freq", "startAt", "interval", "timesOfDay", "until");

    /** HH:mm pattern (hours 00-23, minutes 00-59) — reused from FLAG-4 timesOfDay validation. */
    private static final Pattern HH_MM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /** Floating-civil minute-precision formatter (FLAG-1). */
    private static final DateTimeFormatter CIVIL_MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final MedicationPlanRepository repository;
    private final ObjectMapper objectMapper;

    MedicationPlanSyncCollection(MedicationPlanRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() { return COLLECTION; }

    /**
     * MOTHER-health collection (SD-2 drug-name/dose) — gated by {@code general_health}.
     * Fail-closed: absent consent → per-collection reject, id omitted, rest of batch applies.
     */
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
                .collect(Collectors.toMap(MedicationPlan::getId, p -> (Object) p));
    }

    // -------------------------------------------------------------------------
    // Apply upsert — version-arbitrated LWW (S-A)
    // -------------------------------------------------------------------------

    /**
     * Applies one upsert using the version-arbitrated LWW (S-A) semantics:
     * <ul>
     *   <li>Validation guards checked FIRST (first-fail-wins): name required on live row,
     *       name/dose size caps, scheduleRule grammar.</li>
     *   <li>No server row → INSERT (version:=0 via Hibernate, bumped to 1).</li>
     *   <li>Server row tombstoned → {@code tombstone_won} conflict.</li>
     *   <li>base {@code version == current} → UPDATE + bump version → {@code applied[]}.</li>
     *   <li>base {@code version < current} → {@code server_won} conflict.</li>
     * </ul>
     */
    @Override
    @Transactional
    public SyncApplyResult applyUpsert(UUID userId, Map<String, Object> record, Object existing) {
        UUID id = extractUUID(record, "id");
        if (id == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, null, "validation_error", "id is required"));
        }

        // =================================================================
        // GUARD: name (required on live rows) — RULING 3 sub-code: name_required
        // =================================================================
        byte[] nameCipher = decodeBase64Optional(record, "name");
        if (nameCipher == null) {
            // name_cipher absent → reject (live rows must carry a name;
            // ck_medication_plan__live_name CHECK backstop)
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "name_required"));
        }

        // =================================================================
        // GUARD: name size cap — RULING 3 sub-code: name_too_large
        // =================================================================
        if (nameCipher.length > MAX_CIPHER_BYTES) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "name_too_large"));
        }

        // =================================================================
        // GUARD: dose size cap (optional field) — RULING 3 sub-code: dose_too_large
        // =================================================================
        byte[] doseCipher = decodeBase64Optional(record, "dose");
        if (doseCipher != null && doseCipher.length > MAX_CIPHER_BYTES) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "dose_too_large"));
        }

        // =================================================================
        // GUARD: scheduleRule grammar — RULING 7.1 + RULING 3 sub-code: schedule_rule_invalid
        // null is valid (PRN plan); present-but-invalid → reject
        // =================================================================
        Object scheduleRuleRaw = record.get("scheduleRule");
        String scheduleRuleJson = null; // null = PRN (no schedule)
        if (scheduleRuleRaw != null) {
            String validationError = validateScheduleRule(scheduleRuleRaw);
            if (validationError != null) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "validation_error", "schedule_rule_invalid"));
            }
            // Serialize to JSON string for storage in the jsonb column
            try {
                if (scheduleRuleRaw instanceof String s) {
                    scheduleRuleJson = s;
                } else {
                    scheduleRuleJson = objectMapper.writeValueAsString(scheduleRuleRaw);
                }
            } catch (Exception e) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "validation_error", "schedule_rule_invalid"));
            }
        }

        // Extract active (DB DEFAULT true; not required from client)
        boolean active = extractBoolean(record, "active", true);
        // Extract optional sourceSuggestionStateId (SOFT LINK, no FK — RULING 2)
        UUID sourceSuggestionStateId = extractUUID(record, "sourceSuggestionStateId");
        // Extract clientId (LWW tie-break only)
        UUID clientId = extractUUID(record, "clientId");

        long baseVersion = extractBaseVersion(record);
        MedicationPlan current = (MedicationPlan) existing;

        if (current == null) {
            return insertNew(userId, id, nameCipher, doseCipher, scheduleRuleJson,
                    active, sourceSuggestionStateId, clientId);
        }

        // Tombstone-wins unconditional (D2)
        if (current.getDeletedAt() != null) {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "tombstone_won", toRecord(current)));
        }

        // Version-arbitrated LWW (S-A)
        long currentVersion = current.getVersion() != null ? current.getVersion() : 0L;
        if (baseVersion == currentVersion) {
            return updateExisting(current, userId, id, nameCipher, doseCipher,
                    scheduleRuleJson, active, sourceSuggestionStateId, clientId);
        } else {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", toRecord(current)));
        }
    }

    private SyncApplyResult insertNew(UUID userId, UUID id, byte[] nameCipher, byte[] doseCipher,
                                       String scheduleRuleJson, boolean active,
                                       UUID sourceSuggestionStateId, UUID clientId) {
        MedicationPlan plan = new MedicationPlan();
        plan.setId(id);
        plan.setUserId(userId);
        applyFields(plan, nameCipher, doseCipher, scheduleRuleJson, active,
                sourceSuggestionStateId, clientId);
        try {
            plan = repository.saveAndFlush(plan); // Hibernate @Version seeds to 0 on INSERT
            repository.initVersionToOne(plan.getId()); // bump to 1 (contract §5 pin)
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, plan.getId(), 1L, plan.getUpdatedAt()));
        } catch (Exception ex) {
            // Race: concurrent insert of the same id by another device
            MedicationPlan reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", null));
        }
    }

    private SyncApplyResult updateExisting(MedicationPlan current, UUID userId, UUID id,
                                            byte[] nameCipher, byte[] doseCipher,
                                            String scheduleRuleJson, boolean active,
                                            UUID sourceSuggestionStateId, UUID clientId) {
        applyFields(current, nameCipher, doseCipher, scheduleRuleJson, active,
                sourceSuggestionStateId, clientId);
        try {
            current = repository.saveAndFlush(current);
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, current.getId(),
                            current.getVersion() != null ? current.getVersion() : 0L,
                            current.getUpdatedAt()));
        } catch (OptimisticLockingFailureException ex) {
            MedicationPlan reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            throw ex;
        }
    }

    private void applyFields(MedicationPlan plan, byte[] nameCipher, byte[] doseCipher,
                              String scheduleRuleJson, boolean active,
                              UUID sourceSuggestionStateId, UUID clientId) {
        plan.setNameCipher(nameCipher);
        plan.setDoseCipher(doseCipher);
        plan.setScheduleRule(scheduleRuleJson);
        plan.setActive(active);
        plan.setSourceSuggestionStateId(sourceSuggestionStateId);
        plan.setClientId(clientId);
    }

    // -------------------------------------------------------------------------
    // Apply delete (tombstone-wins, unconditional; crypto-shred name+dose ciphers)
    // -------------------------------------------------------------------------

    /**
     * Tombstones the plan unconditionally.
     *
     * <p>Crypto-shred: sets {@code name_cipher} and {@code dose_cipher} to {@code null}
     * BEFORE setting {@code deleted_at} (PDPA §4.4(A) / ADR RULING 1). The
     * {@code ck_medication_plan__live_name} CHECK permits null name only when
     * {@code deleted_at IS NOT NULL} — this order (shred then tombstone in one UPDATE)
     * satisfies the constraint.
     *
     * <p>Never-seen id → inserts a tombstone skeleton so the deletion propagates to other
     * devices (OQ-SYNC-10). The skeleton has {@code name_cipher = null} which is valid
     * because {@code deleted_at} is set simultaneously.
     */
    @Override
    @Transactional
    public Applied applyDelete(UUID userId, UUID id, Object existing) {
        MedicationPlan item = (MedicationPlan) existing;

        if (item == null) {
            // Never-seen id → insert tombstone skeleton (OQ-SYNC-10)
            MedicationPlan skeleton = new MedicationPlan();
            skeleton.setId(id);
            skeleton.setUserId(userId);
            skeleton.setActive(true);
            // name_cipher and dose_cipher: null is valid for a tombstone (§4.4(A) / CHECK)
            skeleton.setDeletedAt(Instant.now());
            skeleton = repository.saveAndFlush(skeleton);
            repository.initVersionToOne(skeleton.getId()); // version: 0 → 1
            return new Applied(COLLECTION, id, 1L, skeleton.getUpdatedAt());
        }

        if (item.getDeletedAt() != null) {
            // Already tombstoned → idempotent
            return new Applied(COLLECTION, id,
                    item.getVersion() != null ? item.getVersion() : 0L,
                    item.getUpdatedAt());
        }

        // Crypto-shred: zero out name_cipher + dose_cipher BEFORE setting deleted_at
        // (PDPA §4.4(A) / RULING 1 — SD-2 health data; no plaintext residue in tombstone)
        item.setNameCipher(null);
        item.setDoseCipher(null);
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
        List<MedicationPlan> rows = (cursorUpdatedAt == null)
                ? repository.findForPull(userId, since, pageable)
                : repository.findForPullAfterCursor(userId, since, cursorUpdatedAt, cursorId, pageable);
        return rows.stream()
                .map(p -> new PullRecord(p.getId(), p.getUpdatedAt(), p.getDeletedAt(), toRecord(p)))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Field mapping
    // -------------------------------------------------------------------------

    /**
     * Converts a {@link MedicationPlan} to a serialisable {@link Map} for pull
     * {@code updated[]} and conflict {@code serverRecord}.
     *
     * <p>{@code name_cipher} / {@code dose_cipher}: echoed as Base64 strings (INV-M3 —
     * server never decrypts). When null (tombstone with crypto-shred), the key is omitted.
     *
     * <p>{@code scheduleRule}: deserialized from the stored JSON string to a {@link JsonNode}
     * so Jackson re-serializes it as a nested JSON object on the wire (identical to
     * {@code ReminderSyncCollection.toRecord()} for {@code recurrenceRule}). H2 double-encoding
     * handled by TextNode unwrap.
     */
    Map<String, Object> toRecord(MedicationPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("userId", p.getUserId());
        // name_cipher / dose_cipher: Base64-encoded; omitted if null (crypto-shredded tombstone)
        if (p.getNameCipher() != null) {
            m.put("name", Base64.getEncoder().encodeToString(p.getNameCipher()));
        }
        if (p.getDoseCipher() != null) {
            m.put("dose", Base64.getEncoder().encodeToString(p.getDoseCipher()));
        }
        // scheduleRule: stored as JSON string → re-parse to JsonNode for wire (same as reminder)
        try {
            String rrJson = p.getScheduleRule();
            if (rrJson == null) {
                m.put("scheduleRule", null);
            } else {
                JsonNode node = objectMapper.readTree(rrJson);
                if (node.isTextual()) {
                    // H2 double-encodes jsonb values as a JSON string literal — unwrap once
                    node = objectMapper.readTree(node.textValue());
                }
                m.put("scheduleRule", node);
            }
        } catch (Exception ex) {
            log.warn("toRecord: failed to parse scheduleRule for plan id={}; "
                    + "falling back to raw string. cause={}", p.getId(), ex.getMessage());
            m.put("scheduleRule", p.getScheduleRule());
        }
        m.put("active", p.isActive());
        m.put("sourceSuggestionStateId", p.getSourceSuggestionStateId());
        m.put("clientId", p.getClientId());
        m.put("version", p.getVersion() != null ? p.getVersion() : 0L);
        m.put("createdAt", p.getCreatedAt());
        m.put("updatedAt", p.getUpdatedAt());
        m.put("deletedAt", p.getDeletedAt());
        return m;
    }

    // -------------------------------------------------------------------------
    // scheduleRule validation — FLAG-4 grammar (RULING 7.1) + medication additions
    // -------------------------------------------------------------------------

    /**
     * Validates the medication {@code scheduleRule} against the FLAG-4 grammar with the
     * medication-specific adaptations (RULING 7.1):
     * <ol>
     *   <li>Closed grammar: only {@code freq, startAt, interval, timesOfDay, until} allowed.</li>
     *   <li>{@code freq} required; must be {@code one_off | daily | every_n_days}.</li>
     *   <li>{@code startAt} required; must be minute-precision floating-civil
     *       {@code "YYYY-MM-DDTHH:mm"}.</li>
     *   <li>For {@code one_off}: {@code timesOfDay}, {@code interval}, {@code until} FORBIDDEN.</li>
     *   <li>For {@code daily}: {@code timesOfDay} required+valid; {@code interval} absent or 1;
     *       {@code until} optional+valid (≥ {@code startAt.date}).</li>
     *   <li>For {@code every_n_days}: {@code timesOfDay} required+valid; {@code interval}
     *       required and <strong>{@code >= 2}</strong> (interval=1 rejected — canonicalise-to-daily
     *       is CLIENT concern, RULING 7.1); {@code until} optional+valid.</li>
     * </ol>
     *
     * <p>A {@code null} rawRule (PRN plan) bypasses this method entirely (the caller returns
     * {@code null} = valid without invoking this validator).
     *
     * @param rawRule the raw schedule_rule value from the push payload ({@code Map} or
     *                {@code String}) — never {@code null} when this method is called
     * @return {@code null} if valid; {@code "schedule_rule_invalid"} if not
     */
    private String validateScheduleRule(Object rawRule) {
        // Parse to Map<String, Object>
        Map<String, Object> rule;
        if (rawRule instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            rule = cast;
        } else {
            try {
                rule = objectMapper.readValue(rawRule.toString(),
                        new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return "schedule_rule_invalid";
            }
        }

        // Rule 6: closed grammar — no unknown keys
        for (String key : rule.keySet()) {
            if (!SCHEDULE_RULE_KNOWN_KEYS.contains(key)) {
                return "schedule_rule_invalid";
            }
        }

        // Rule 1: freq required + closed enum (not weekly for medication)
        String freq = rule.get("freq") != null ? rule.get("freq").toString() : null;
        if (freq == null || !VALID_SCHEDULE_FREQS.contains(freq)) {
            return "schedule_rule_invalid";
        }

        // Rule 2: startAt required, must be "YYYY-MM-DDTHH:mm" (folded-in civil anchor)
        Object startAtRaw = rule.get("startAt");
        if (startAtRaw == null) {
            return "schedule_rule_invalid";
        }
        LocalDateTime startAt = parseCivilMinute(startAtRaw.toString());
        if (startAt == null) {
            return "schedule_rule_invalid";
        }

        Object intervalRaw = rule.get("interval");
        Object timesOfDayRaw = rule.get("timesOfDay");
        Object untilRaw = rule.get("until");

        return switch (freq) {
            case "one_off" -> {
                // timesOfDay, interval, until all FORBIDDEN for one_off
                if (timesOfDayRaw != null) yield "schedule_rule_invalid";
                if (intervalRaw != null)   yield "schedule_rule_invalid";
                if (untilRaw != null)      yield "schedule_rule_invalid";
                yield null;
            }
            case "daily" -> {
                // timesOfDay required+valid; interval absent or 1; until optional valid
                String timesErr = validateTimesOfDay(timesOfDayRaw);
                if (timesErr != null) yield "schedule_rule_invalid";
                if (intervalRaw != null) {
                    int interval;
                    try { interval = ((Number) intervalRaw).intValue(); }
                    catch (Exception e) { yield "schedule_rule_invalid"; }
                    if (interval != 1) yield "schedule_rule_invalid";
                }
                if (untilRaw != null) {
                    LocalDate until = parseLocalDate(untilRaw.toString());
                    if (until == null || until.isBefore(startAt.toLocalDate())) {
                        yield "schedule_rule_invalid";
                    }
                }
                yield null;
            }
            case "every_n_days" -> {
                // timesOfDay required+valid; interval required >= 2; until optional valid
                String timesErr = validateTimesOfDay(timesOfDayRaw);
                if (timesErr != null) yield "schedule_rule_invalid";
                if (intervalRaw == null) yield "schedule_rule_invalid";
                int interval;
                try { interval = ((Number) intervalRaw).intValue(); }
                catch (Exception e) { yield "schedule_rule_invalid"; }
                // Medication-specific rule: interval >= 2 (interval=1 canonicalized→daily
                // is CLIENT concern; server REJECTS it — RULING 7.1 §G-1 CLOSED)
                if (interval < 2) yield "schedule_rule_invalid";
                if (untilRaw != null) {
                    LocalDate until = parseLocalDate(untilRaw.toString());
                    if (until == null || until.isBefore(startAt.toLocalDate())) {
                        yield "schedule_rule_invalid";
                    }
                }
                yield null;
            }
            // Should not reach here since freq is validated against VALID_SCHEDULE_FREQS
            default -> "schedule_rule_invalid";
        };
    }

    /**
     * Validates {@code timesOfDay}: non-empty, each entry matches {@code HH:mm} pattern,
     * no duplicates, strictly ascending order. Reuses the FLAG-4 timesOfDay discipline
     * from {@link ReminderSyncCollection}.
     */
    private String validateTimesOfDay(Object timesOfDayRaw) {
        if (!(timesOfDayRaw instanceof List<?>)) {
            return "invalid";
        }
        @SuppressWarnings("unchecked")
        List<Object> times = (List<Object>) timesOfDayRaw;
        if (times.isEmpty()) {
            return "invalid";
        }
        String prev = null;
        for (Object t : times) {
            String ts = t != null ? t.toString() : null;
            if (ts == null || !HH_MM.matcher(ts).matches()) {
                return "invalid";
            }
            if (prev != null) {
                if (ts.equals(prev))       return "invalid"; // duplicate
                if (ts.compareTo(prev) < 0) return "invalid"; // not ascending
            }
            prev = ts;
        }
        return null; // valid
    }

    // -------------------------------------------------------------------------
    // Field-extraction utilities
    // -------------------------------------------------------------------------

    private static UUID extractUUID(Map<String, Object> record, String key) {
        Object val = record.get(key);
        if (val == null) return null;
        try { return UUID.fromString(val.toString()); } catch (Exception e) { return null; }
    }

    private static boolean extractBoolean(Map<String, Object> record, String key,
                                          boolean defaultValue) {
        Object val = record.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }

    private static long extractBaseVersion(Map<String, Object> record) {
        Object val = record.get("version");
        if (val == null) return 0L;
        try { return ((Number) val).longValue(); } catch (Exception e) { return 0L; }
    }

    /**
     * Decodes a Base64-encoded cipher field from the push record to {@code byte[]}.
     * Returns {@code null} if the field is absent, null, or blank.
     * Never parses the content (INV-M3 / G4: opaque ciphertext).
     */
    private static byte[] decodeBase64Optional(Map<String, Object> record, String key) {
        Object val = record.get(key);
        if (val == null) return null;
        String s = val.toString();
        if (s.isBlank()) return null;
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            // Not valid Base64 → treat as raw UTF-8 bytes (MVP passthrough / no-op cipher)
            return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * Parses a floating-civil minute-precision string {@code "YYYY-MM-DDTHH:mm"}.
     * Returns {@code null} if null, blank, or unparseable.
     */
    private static LocalDateTime parseCivilMinute(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s, CIVIL_MINUTE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate parseLocalDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
