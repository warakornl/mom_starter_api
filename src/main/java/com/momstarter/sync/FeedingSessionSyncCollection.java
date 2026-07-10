package com.momstarter.sync;

import com.momstarter.feeding.FeedingSession;
import com.momstarter.feeding.FeedingSessionRepository;
import com.momstarter.sync.dto.Applied;
import com.momstarter.sync.dto.Conflict;
import com.momstarter.sync.dto.Rejected;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
 * {@link SyncCollection} implementation for the {@code feedingSessions} collection.
 *
 * <h3>Record class: immutable-event union</h3>
 * <p>Each session is a distinct UUID; once persisted the row is NEVER overwritten.
 * Re-push of the same id is an idempotent no-op (echoes current version/updatedAt).
 * Mirrors {@link KickCountSessionSyncCollection} / MedicationLogSyncCollection pattern.
 *
 * <h3>Dual consent gate</h3>
 * <p>{@link #perCollectionConsentType()} returns {@code "general_health"} (primary).
 * {@link #additionalCollectionConsentTypes()} returns {@code ["infant_feeding"]}.
 * Both must be granted for the collection to be processed. {@link com.momstarter.sync.SyncService}
 * checks both before dispatching to this collection (no per-record consent check needed here).
 *
 * <h3>Validation guards (BEFORE any DB op)</h3>
 * <ol>
 *   <li>{@code id} present and valid UUID.</li>
 *   <li>{@code kind} in {@code breastfeed | pump | formula} → {@code kind_invalid}.</li>
 *   <li>{@code startedAt} present, floating-civil {@code YYYY-MM-DDTHH:mm} → {@code started_at_required}.</li>
 *   <li>{@code amountSubUnits} null for non-formula → {@code amount_sub_units_formula_only}.</li>
 *   <li>{@code amountSubUnits} ≥ 0 if present → {@code amount_sub_units_range}.</li>
 *   <li>{@code durationSeconds} ≥ 0 if present → {@code duration_range}.</li>
 *   <li>{@code note_cipher} ≤ 8192 bytes → {@code note_too_large}.</li>
 * </ol>
 *
 * <h3>Tombstone + crypto-shred</h3>
 * <p>On delete, {@link FeedingSession#setNoteCipher(byte[])} is set to {@code null}
 * (PDPA ม.33 / pdpa-assessment ruling 5 / V20260710000020).
 *
 * <h3>INV-ASD-4 / INV-ASD-8 / INV-ASD-9</h3>
 * <p>{@link #toRecord(FeedingSession)} emits ZERO supply-side linkage fields.
 * No {@code supplyItemId}, no {@code fedAt}, no {@code usesRemainingInOpenContainer},
 * no activity-side cross-reference.
 */
@Component
class FeedingSessionSyncCollection implements SyncCollection {

    private static final String COLLECTION = "feedingSessions";

    private static final Set<String> VALID_KINDS = Set.of("breastfeed", "pump", "formula");
    private static final Set<String> VALID_SIDES = Set.of("left", "right", "both");
    private static final int MAX_NOTE_BYTES = 8192;

    /**
     * Floating-civil minute-precision formatter (FLAG-1).
     * {@code startedAt} uses this format on both wire and storage.
     */
    private static final DateTimeFormatter CIVIL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final FeedingSessionRepository repository;

    FeedingSessionSyncCollection(FeedingSessionRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() { return COLLECTION; }

    /**
     * Primary per-collection consent gate.
     * Feeds are MOTHER+INFANT-health data → gated by {@code general_health} (primary).
     */
    @Override
    public String perCollectionConsentType() { return "general_health"; }

    /**
     * Additional per-collection consent type: {@code infant_feeding} (SD-10).
     * Both {@code general_health} AND {@code infant_feeding} must be granted.
     */
    @Override
    public List<String> additionalCollectionConsentTypes() {
        return List.of("infant_feeding");
    }

    // -------------------------------------------------------------------------
    // Batch pre-load
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Object> loadExisting(UUID userId, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return repository.findByUserIdAndIdIn(userId, ids)
                .stream()
                .collect(Collectors.toMap(FeedingSession::getId, s -> (Object) s));
    }

    // -------------------------------------------------------------------------
    // Apply upsert — immutable-event union semantics
    // -------------------------------------------------------------------------

    /**
     * Applies one upsert using immutable-event union:
     * <ul>
     *   <li>All validation guards checked BEFORE any DB operation.</li>
     *   <li>Never-seen id → INSERT, {@code version:=1} → {@code applied[]}.</li>
     *   <li>Existing live id → idempotent no-op; echo current version/updatedAt
     *       → {@code applied[]} (version NEVER bumped — immutable event).</li>
     *   <li>Existing tombstoned id → {@code tombstone_won} conflict.</li>
     * </ul>
     */
    @Override
    @Transactional
    public SyncApplyResult applyUpsert(UUID userId, Map<String, Object> record, Object existing) {

        // --- GUARD 1: id ---
        UUID id = extractUUID(record, "id");
        if (id == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, null, "validation_error", "id is required"));
        }

        // --- GUARD 2: kind ---
        String kind = extractString(record, "kind");
        if (kind == null || !VALID_KINDS.contains(kind)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "kind_invalid"));
        }

        // --- GUARD 3: startedAt (required, floating-civil YYYY-MM-DDTHH:mm, FLAG-1) ---
        String startedAtStr = extractString(record, "startedAt");
        LocalDateTime startedAt = parseCivil(startedAtStr);
        if (startedAt == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "started_at_required"));
        }

        // --- GUARD 4: amountSubUnits cross-field constraint ---
        // amountSubUnits is meaningful ONLY for kind=formula; must be null for breastfeed/pump.
        // (Mirrors DB CHECK (kind='formula' OR amount_sub_units IS NULL).)
        Object asuRaw = record.get("amountSubUnits");
        Integer amountSubUnits = null;
        if (asuRaw != null) {
            if (!"formula".equals(kind)) {
                // Non-formula with amountSubUnits set → reject
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "validation_error", "amount_sub_units_formula_only"));
            }
            try {
                amountSubUnits = ((Number) asuRaw).intValue();
            } catch (ClassCastException | NullPointerException e) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "validation_error", "amount_sub_units_range"));
            }
            // --- GUARD 5: amountSubUnits ≥ 0 ---
            if (amountSubUnits < 0) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "validation_error", "amount_sub_units_range"));
            }
        }

        // --- GUARD 6: durationSeconds ≥ 0 if present ---
        Object durRaw = record.get("durationSeconds");
        Integer durationSeconds = null;
        if (durRaw != null) {
            try {
                durationSeconds = ((Number) durRaw).intValue();
            } catch (ClassCastException | NullPointerException e) {
                // malformed → treat as absent
            }
            if (durationSeconds != null && durationSeconds < 0) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "validation_error", "duration_range"));
            }
        }

        // --- GUARD 7: note_cipher size ≤ 8192 bytes ---
        String noteBase64 = extractString(record, "note");
        byte[] noteCipher = null;
        if (noteBase64 != null && !noteBase64.isBlank()) {
            try {
                noteCipher = Base64.getDecoder().decode(noteBase64);
            } catch (IllegalArgumentException e) {
                noteCipher = noteBase64.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            if (noteCipher.length > MAX_NOTE_BYTES) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "validation_error", "note_too_large"));
            }
        }

        // --- All guards passed — immutable-event union ---
        FeedingSession current = (FeedingSession) existing;

        if (current == null) {
            return insertNew(userId, id, kind, startedAt, amountSubUnits, durationSeconds,
                    noteCipher, record);
        }

        if (current.getDeletedAt() != null) {
            // Tombstone beats re-create (tombstone-wins)
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "tombstone_won", toRecord(current)));
        }

        // Existing live row → idempotent no-op (immutable event: never rewrite fields)
        return new SyncApplyResult.Success(
                new Applied(COLLECTION, current.getId(),
                        current.getVersion() != null ? current.getVersion() : 1L,
                        current.getUpdatedAt()));
    }

    private SyncApplyResult insertNew(UUID userId, UUID id, String kind, LocalDateTime startedAt,
                                       Integer amountSubUnits, Integer durationSeconds,
                                       byte[] noteCipher, Map<String, Object> record) {
        FeedingSession s = new FeedingSession();
        s.setId(id);
        s.setUserId(userId);
        s.setKind(kind);
        s.setStartedAt(startedAt);
        s.setAmountSubUnits(amountSubUnits);
        s.setDurationSeconds(durationSeconds);
        s.setNoteCipher(noteCipher);

        // side: optional, must be left|right|both if present
        String sideStr = extractString(record, "side");
        if (sideStr != null && VALID_SIDES.contains(sideStr)) {
            s.setSide(sideStr);
        }

        // volumeMl: optional numeric
        Object volRaw = record.get("volumeMl");
        if (volRaw != null) {
            try {
                s.setVolumeMl(new BigDecimal(volRaw.toString()));
            } catch (Exception ignored) {}
        }

        // clientId
        UUID clientIdParsed = extractUUID(record, "clientId");
        s.setClientId(clientIdParsed);

        try {
            s = repository.saveAndFlush(s);
            repository.initVersionToOne(s.getId());
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, s.getId(), 1L, s.getUpdatedAt()));
        } catch (Exception ex) {
            // Race: concurrent insert of same id for this user
            FeedingSession reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                // Immutable event: echo reloaded state (no-op)
                return new SyncApplyResult.Success(
                        new Applied(COLLECTION, reloaded.getId(),
                                reloaded.getVersion() != null ? reloaded.getVersion() : 1L,
                                reloaded.getUpdatedAt()));
            }
            // UUID exists for another user (IDOR attempt)
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", null));
        }
    }

    // -------------------------------------------------------------------------
    // Apply delete — tombstone-wins, unconditional; crypto-shred note_cipher
    // -------------------------------------------------------------------------

    /**
     * Tombstones the record unconditionally.
     * On tombstone: {@link FeedingSession#setNoteCipher(byte[])} → {@code null}
     * (crypto-shred, PDPA ม.33 / pdpa-assessment ruling 5 / V20260710000020).
     * Never-seen id → inserts a tombstone skeleton (OQ-SYNC-10).
     */
    @Override
    @Transactional
    public Applied applyDelete(UUID userId, UUID id, Object existing) {
        FeedingSession item = (FeedingSession) existing;

        if (item == null) {
            // Never-seen id → tombstone skeleton (OQ-SYNC-10)
            FeedingSession skeleton = new FeedingSession();
            skeleton.setId(id);
            skeleton.setUserId(userId);
            skeleton.setKind("formula"); // placeholder (satisfies NOT NULL)
            skeleton.setStartedAt(LocalDateTime.now().withSecond(0).withNano(0));
            skeleton.setDeletedAt(Instant.now());
            // noteCipher = null (nothing to shred in skeleton)
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

        // Crypto-shred: zero out note_cipher BEFORE stamping deletedAt
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
        List<FeedingSession> rows = (cursorUpdatedAt == null)
                ? repository.findForPull(userId, since, pageable)
                : repository.findForPullAfterCursor(userId, since, cursorUpdatedAt, cursorId, pageable);
        return rows.stream()
                .map(s -> new PullRecord(s.getId(), s.getUpdatedAt(), s.getDeletedAt(), toRecord(s)))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Field mapping — INV-ASD-4/8/9: ZERO supply-side linkage emitted
    // -------------------------------------------------------------------------

    /**
     * Converts a {@link FeedingSession} to a serialisable map for pull and conflict records.
     *
     * <p>INV-ASD-4 / INV-ASD-8 / INV-ASD-9: this map carries NO supply-side linkage.
     * No {@code supplyItemId}, no {@code fedAt}, no {@code usesRemainingInOpenContainer}.
     * The server NEVER emits per-feed activity linkage to the supplies side.
     *
     * <p>{@code note_cipher} is echoed as Base64 under key {@code "note"} (contract field name).
     * When {@code null} (tombstone / no note), the key is omitted.
     */
    Map<String, Object> toRecord(FeedingSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("userId", s.getUserId());
        m.put("kind", s.getKind());
        m.put("side", s.getSide());
        m.put("startedAt", s.getStartedAt() != null ? s.getStartedAt().format(CIVIL_FMT) : null);
        m.put("durationSeconds", s.getDurationSeconds());
        m.put("volumeMl", s.getVolumeMl());
        // amountSubUnits: only meaningful for formula; null for breastfeed/pump (INV-ASD-4)
        m.put("amountSubUnits", s.getAmountSubUnits());
        if (s.getNoteCipher() != null) {
            m.put("note", Base64.getEncoder().encodeToString(s.getNoteCipher()));
        }
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
        try { return LocalDateTime.parse(s, CIVIL_FMT); }
        catch (DateTimeParseException e) { return null; }
    }
}
