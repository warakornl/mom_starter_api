package com.momstarter.sync;

import com.momstarter.selflog.SelfLog;
import com.momstarter.selflog.SelfLogRepository;
import com.momstarter.sync.dto.Applied;
import com.momstarter.sync.dto.Conflict;
import com.momstarter.sync.dto.Rejected;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
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
 * {@link SyncCollection} implementation for the {@code selfLogs} collection.
 *
 * <h3>Record class: immutable-event union (spec D2)</h3>
 * <p>Each self-log is a distinct client-generated UUIDv4; the row is NEVER overwritten once
 * created. Re-sending the same id is an idempotent no-op (echo current version/updatedAt without
 * bumping or modifying any field). Mirrors {@code KickCountSessionSyncCollection} — the declared
 * sibling create-only union pattern.
 *
 * <h3>Apply-path guards (REJECT BEFORE INSERT — first-fail-wins)</h3>
 * <ol>
 *   <li>{@code id} absent → validation_error</li>
 *   <li>{@code metricType} absent → {@code metric_type_required}</li>
 *   <li>{@code metricType} ∉ valid set → {@code unknown_metric_type}</li>
 *   <li>{@code loggedAt} absent → {@code logged_at_required}</li>
 *   <li>{@code loggedAt} malformed → {@code logged_at_malformed}</li>
 *   <li>All 4 value fields null → {@code empty_value} (ADR Decision 3 / G-2; pure IS-NULL,
 *       reads no ciphertext — G4 / INV-S2)</li>
 *   <li>note decoded size > 8192 bytes → {@code note_too_large}</li>
 * </ol>
 * Closed {@code validation_error.details} sub-code enum (ADR Decision 4 / G-3 ratified):
 * {@code unknown_metric_type} · {@code metric_type_required} · {@code logged_at_required} ·
 * {@code logged_at_malformed} · {@code note_too_large} · {@code empty_value}.
 *
 * <h3>Value column opaqueness (INV-S2 / G4 / ADR Decision 1)</h3>
 * <p>{@code value_numeric}, {@code value_numeric_secondary}, {@code value_text},
 * {@code note_cipher} are {@code bytea} columns (MVP: plaintext bytes; real AES-GCM at KMS
 * milestone, zero schema change). The server NEVER parses, queries, or aggregates these columns.
 * Values arrive as Base64-encoded strings; the server only decodes to {@code byte[]} for storage
 * and echoes back verbatim for the client to decrypt.
 *
 * <h3>Crypto-shred on tombstone (§4.4(A) / PDPA ruling 5a)</h3>
 * <p>When tombstoned ({@code deleted_at} set), all four byte[] value columns are immediately set
 * to {@code null} so the tombstone row retains no recoverable plaintext. Mirrors the
 * {@code kick_count_session.note_cipher} shred behaviour.
 *
 * <h3>Per-collection consent gate</h3>
 * <p>MOTHER-health collection → gated by {@code general_health}. Per-collection reject shape:
 * {@code rejected[]{collection:"selfLogs", code:"consent_required", details:"general_health"}}
 * with {@code id} omitted (whole-collection reject). Rest of batch continues to apply.
 */
@Component
class SelfLogSyncCollection implements SyncCollection {

    private static final String COLLECTION = "selfLogs";

    /** Maximum allowed note_cipher decoded byte size (mirrors kick_count OQ-K-B = 8 KB). */
    private static final int MAX_NOTE_BYTES = 8192;

    /** Accepted metricType values (DB CHECK backstop; spec §1). */
    private static final Set<String> VALID_METRIC_TYPES = Set.of(
            "weight", "blood_pressure", "swelling", "lochia", "symptom");

    /**
     * Floating-civil minute-precision formatter (FLAG-1).
     * {@code loggedAt} uses this format — identical to kick_count's CIVIL_MINUTE_FMT.
     */
    private static final DateTimeFormatter CIVIL_MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final SelfLogRepository repository;

    SelfLogSyncCollection(SelfLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() { return COLLECTION; }

    /** MOTHER-health collection — gated by {@code general_health}. */
    @Override
    public String perCollectionConsentType() { return "general_health"; }

    // -------------------------------------------------------------------------
    // Batch pre-load
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Object> loadExisting(UUID userId, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return repository.findByUserIdAndIdIn(userId, ids)
                .stream()
                .collect(Collectors.toMap(SelfLog::getId, s -> (Object) s));
    }

    // -------------------------------------------------------------------------
    // Apply upsert — immutable-event union
    // -------------------------------------------------------------------------

    /**
     * Applies one upsert using the <strong>immutable-event union</strong> semantics:
     * <ul>
     *   <li>All validation guards checked BEFORE any DB operation (first-fail-wins).</li>
     *   <li>Never-seen id → INSERT, {@code version:=1} → {@code applied[]}.</li>
     *   <li>Existing live id → idempotent no-op; echo current version/updatedAt
     *       → {@code applied[]} (version NEVER bumped — immutable event).</li>
     *   <li>Existing tombstoned id → {@code tombstone_won} conflict (tombstone beats re-create).</li>
     * </ul>
     */
    @Override
    @Transactional
    public SyncApplyResult applyUpsert(UUID userId, Map<String, Object> record, Object existing) {

        // --- Extract id first (needed for all rejection reports) ---
        UUID id = extractUUID(record, "id");
        if (id == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, null, "validation_error", "id is required"));
        }

        // =================================================================
        // GUARD 1 + 2: metricType — required + enum guard
        // =================================================================
        String metricType = extractString(record, "metricType");
        if (metricType == null || metricType.isBlank()) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "metric_type_required"));
        }
        if (!VALID_METRIC_TYPES.contains(metricType)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "unknown_metric_type"));
        }

        // =================================================================
        // GUARD 3 + 4: loggedAt — required, floating-civil YYYY-MM-DDTHH:mm (FLAG-1)
        // =================================================================
        String loggedAtStr = extractString(record, "loggedAt");
        if (loggedAtStr == null || loggedAtStr.isBlank()) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "logged_at_required"));
        }
        LocalDateTime loggedAt = parseCivil(loggedAtStr);
        if (loggedAt == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "logged_at_malformed"));
        }

        // =================================================================
        // Decode value fields (opaque bytes — INV-S2 / G4; never parse content)
        // Values arrive as Base64-encoded strings in the JSON payload.
        // =================================================================
        byte[] valueNumeric          = decodeBase64Optional(record, "valueNumeric");
        byte[] valueNumericSecondary = decodeBase64Optional(record, "valueNumericSecondary");
        byte[] valueText             = decodeBase64Optional(record, "valueText");
        byte[] noteCipher            = decodeBase64Optional(record, "note");

        // =================================================================
        // GUARD 5: empty_value — all four value fields null (ADR Decision 3 / G-2)
        // Pure IS-NULL structural check; reads no ciphertext (G4 / INV-S2).
        // =================================================================
        if (valueNumeric == null && valueNumericSecondary == null
                && valueText == null && noteCipher == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "empty_value"));
        }

        // =================================================================
        // GUARD 6: note_cipher — optional; if present max decoded size = 8192 bytes
        // Server NEVER parses note content; only checks decoded byte length.
        // =================================================================
        if (noteCipher != null && noteCipher.length > MAX_NOTE_BYTES) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "note_too_large"));
        }

        // =================================================================
        // All guards passed — apply immutable-event union semantics
        // =================================================================
        SelfLog current = (SelfLog) existing;

        if (current == null) {
            // Never-seen id → INSERT
            return insertNew(userId, id, metricType, loggedAt,
                    valueNumeric, valueNumericSecondary, valueText, noteCipher, record);
        }

        if (current.getDeletedAt() != null) {
            // Tombstone beats re-create (tombstone-wins)
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "tombstone_won", toRecord(current)));
        }

        // Existing live row → idempotent no-op (immutable event: never rewrite fields)
        // Echo current version/updatedAt; version is NOT bumped (D2)
        return new SyncApplyResult.Success(
                new Applied(COLLECTION, current.getId(),
                        current.getVersion() != null ? current.getVersion() : 1L,
                        current.getUpdatedAt()));
    }

    private SyncApplyResult insertNew(UUID userId, UUID id, String metricType,
                                       LocalDateTime loggedAt,
                                       byte[] valueNumeric, byte[] valueNumericSecondary,
                                       byte[] valueText, byte[] noteCipher,
                                       Map<String, Object> record) {
        SelfLog s = new SelfLog();
        s.setId(id);
        s.setUserId(userId);
        s.setMetricType(metricType);
        s.setLoggedAt(loggedAt);

        // Value columns: stored verbatim as received ciphertext (D3 / INV-S2 / ADR Decision 1)
        s.setValueNumeric(valueNumeric);
        s.setValueNumericSecondary(valueNumericSecondary);
        s.setValueText(valueText);
        s.setNoteCipher(noteCipher);

        // unit: non-sensitive plaintext display label; stored verbatim
        s.setUnit(extractString(record, "unit"));

        // clientId: originating device UUID (LWW tie-break only)
        s.setClientId(extractUUID(record, "clientId"));

        try {
            s = repository.saveAndFlush(s);
            // Contract §5: genuine create → version:=1 (Hibernate @Version seeds to 0)
            repository.initVersionToOne(s.getId());
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, s.getId(), 1L, s.getUpdatedAt()));
        } catch (Exception ex) {
            // Race: another thread/device concurrently inserted the same id for this user
            SelfLog reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                // Immutable event no-op: echo reloaded state
                return new SyncApplyResult.Success(
                        new Applied(COLLECTION, reloaded.getId(),
                                reloaded.getVersion() != null ? reloaded.getVersion() : 1L,
                                reloaded.getUpdatedAt()));
            }
            // UUID collision with another user (IDOR attempt) → server_won, no record exposed
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", null));
        }
    }

    // -------------------------------------------------------------------------
    // Apply delete — tombstone-wins, unconditional; crypto-shred all byte[] columns
    // -------------------------------------------------------------------------

    /**
     * Tombstones the record unconditionally.
     *
     * <p>On tombstone: all four byte[] value columns ({@code valueNumeric},
     * {@code valueNumericSecondary}, {@code valueText}, {@code noteCipher}) are set to
     * {@code null} (crypto-shred — PDPA §4.4(A) / ruling 5a) so the tombstone row retains
     * no recoverable plaintext.
     *
     * <p>Never-seen id → inserts a tombstone skeleton so the deletion propagates to other
     * devices (OQ-SYNC-10).
     */
    @Override
    @Transactional
    public Applied applyDelete(UUID userId, UUID id, Object existing) {
        SelfLog item = (SelfLog) existing;

        if (item == null) {
            // Never-seen id → insert tombstone skeleton (OQ-SYNC-10)
            SelfLog skeleton = new SelfLog();
            skeleton.setId(id);
            skeleton.setUserId(userId);
            skeleton.setMetricType("weight");  // placeholder (NOT NULL constraint)
            skeleton.setLoggedAt(LocalDateTime.now().withSecond(0).withNano(0));
            skeleton.setDeletedAt(Instant.now());
            // All value columns: null (nothing to shred for a skeleton)
            skeleton = repository.saveAndFlush(skeleton);
            repository.initVersionToOne(skeleton.getId());
            return new Applied(COLLECTION, id, 1L, skeleton.getUpdatedAt());
        }

        if (item.getDeletedAt() != null) {
            // Already tombstoned → idempotent
            return new Applied(COLLECTION, id,
                    item.getVersion() != null ? item.getVersion() : 0L,
                    item.getUpdatedAt());
        }

        // Crypto-shred: zero out all four byte[] value columns BEFORE setting deletedAt
        // (PDPA §4.4(A) / crypto-shred ruling 5a — no plaintext residue in tombstone row)
        item.setValueNumeric(null);
        item.setValueNumericSecondary(null);
        item.setValueText(null);
        item.setNoteCipher(null);
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
        List<SelfLog> rows = (cursorUpdatedAt == null)
                ? repository.findForPull(userId, since, pageable)
                : repository.findForPullAfterCursor(userId, since, cursorUpdatedAt, cursorId, pageable);
        return rows.stream()
                .map(s -> new PullRecord(s.getId(), s.getUpdatedAt(), s.getDeletedAt(), toRecord(s)))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Field mapping
    // -------------------------------------------------------------------------

    /**
     * Converts a {@link SelfLog} to a serialisable {@link Map} for use in pull
     * {@code updated[]} and conflict {@code serverRecord}.
     *
     * <p>Value columns ({@code valueNumeric}, {@code valueNumericSecondary}, {@code valueText},
     * {@code note_cipher}) are echoed as Base64 strings. The client decrypts locally
     * (INV-S2: server never parses). When a column is {@code null} (tombstone or unused per
     * metricType), the key is omitted ({@code @JsonInclude(NON_NULL)} pattern in mapping).
     */
    Map<String, Object> toRecord(SelfLog s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("userId", s.getUserId());
        m.put("metricType", s.getMetricType());
        m.put("loggedAt", s.getLoggedAt() != null
                ? s.getLoggedAt().format(CIVIL_MINUTE_FMT) : null);
        // Value columns: echoed as Base64; omitted if null (tombstone or absent-for-type)
        if (s.getValueNumeric() != null) {
            m.put("valueNumeric",
                    Base64.getEncoder().encodeToString(s.getValueNumeric()));
        }
        if (s.getValueNumericSecondary() != null) {
            m.put("valueNumericSecondary",
                    Base64.getEncoder().encodeToString(s.getValueNumericSecondary()));
        }
        if (s.getValueText() != null) {
            m.put("valueText",
                    Base64.getEncoder().encodeToString(s.getValueText()));
        }
        if (s.getNoteCipher() != null) {
            m.put("note", Base64.getEncoder().encodeToString(s.getNoteCipher()));
        }
        m.put("unit", s.getUnit());
        m.put("clientId", s.getClientId());
        m.put("version", s.getVersion() != null ? s.getVersion() : 0L);
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        m.put("deletedAt", s.getDeletedAt());
        return m;
    }

    // -------------------------------------------------------------------------
    // Utilities
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

    private static LocalDateTime parseCivil(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s, CIVIL_MINUTE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Decodes a Base64-encoded value field from the JSON record to {@code byte[]}.
     * Returns {@code null} if the field is absent, null, or blank — never parses the content
     * (INV-S2 / G4: opaque ciphertext; ADR Decision 1).
     */
    private static byte[] decodeBase64Optional(Map<String, Object> record, String key) {
        String val = extractString(record, key);
        if (val == null || val.isBlank()) return null;
        try {
            return Base64.getDecoder().decode(val);
        } catch (IllegalArgumentException e) {
            // Not valid Base64 → treat as raw UTF-8 bytes (mirrors kick_count note handling)
            return val.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
