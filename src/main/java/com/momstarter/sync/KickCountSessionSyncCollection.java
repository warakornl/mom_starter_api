package com.momstarter.sync;

import com.momstarter.kickcount.KickCountSession;
import com.momstarter.kickcount.KickCountSessionRepository;
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
 * {@link SyncCollection} implementation for the {@code kickCountSessions} collection.
 *
 * <h3>Record class: immutable-event union (§4 / D3)</h3>
 * <p>Each session is a distinct UUID; once {@code completed} and persisted the row is
 * NEVER overwritten. Re-sending the same id is an idempotent no-op (the server echoes the
 * current {@code version}/{@code updatedAt} without bumping or modifying any field).
 * This is the {@code MedicationLog}/{@code SelfLog} pattern (create-only union), NOT LWW.
 *
 * <h3>Terminal-status push guard (mirrors {@code ReminderOccurrence} 🟡-2)</h3>
 * <p>Only {@code status = completed} is accepted. A pushed {@code in_progress} or
 * {@code cancelled} → {@code rejected[]{code:"validation_error", details:"non_terminal_status"}}.
 * {@code in_progress}/{@code cancelled} are local-only draft lifecycle states (OQ-K1).
 *
 * <h3>Apply-path guards (database-reviewer follow-up — REJECT BEFORE INSERT)</h3>
 * <p>All 8 validation sub-codes are checked BEFORE any DB operation to prevent the
 * {@code CHECK (status = 'completed')} / {@code CHECK (target_count = 10)} DB constraints
 * from surfacing as a 500 error. Guard order (first-fail wins):
 * <ol>
 *   <li>{@code non_terminal_status} — status ≠ completed</li>
 *   <li>{@code started_at_required} — startedAt missing/malformed</li>
 *   <li>{@code ended_before_started} — endedAt absent on completed (B1) OR endedAt < startedAt</li>
 *   <li>{@code movement_count_required} — movementCount missing/malformed/non-integer (G2)</li>
 *   <li>{@code movement_count_range} — movementCount present but &lt; 0</li>
 *   <li>{@code target_count_locked} — targetCount ≠ 10 (MVP lock, D5)</li>
 *   <li>{@code duration_range} — durationSeconds present but &lt; 0</li>
 *   <li>{@code note_too_large} — note_cipher decoded size &gt; 8192 bytes (OQ-K-B resolved)</li>
 * </ol>
 * This 8-sub-code enum is FROZEN (contract B2); QA asserts these exact strings.
 *
 * <h3>Verbatim storage (DRIFT-1)</h3>
 * <p>{@link KickCountSession#getDurationSeconds()} and
 * {@link KickCountSession#getGestationalWeekAtStart()} are stored exactly as received.
 * The server NEVER recomputes either from {@code startedAt}/{@code endedAt}.
 *
 * <h3>note_cipher crypto-shred on tombstone (K-2 / pdpa ruling 5a)</h3>
 * <p>When a session is tombstoned ({@code deleted_at} set), {@link KickCountSession#getNoteCipher()}
 * is immediately set to {@code null} so the tombstone row retains no recoverable plaintext.
 *
 * <h3>Per-collection consent gate (K-1)</h3>
 * <p>MOTHER-health collection → gated by {@code general_health}.
 */
@Component
class KickCountSessionSyncCollection implements SyncCollection {

    private static final String COLLECTION = "kickCountSessions";

    /** MVP-locked target count (D5 / K-5a). */
    private static final int TARGET_COUNT_LOCKED = 10;

    /** Maximum allowed note_cipher ciphertext size in bytes (OQ-K-B resolved = 8 KB). */
    private static final int MAX_NOTE_BYTES = 8192;

    /**
     * Floating-civil minute-precision formatter (FLAG-1).
     * Both {@code startedAt} and {@code endedAt} use this format.
     */
    private static final DateTimeFormatter CIVIL_MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final KickCountSessionRepository repository;

    KickCountSessionSyncCollection(KickCountSessionRepository repository) {
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
                .collect(Collectors.toMap(KickCountSession::getId, s -> (Object) s));
    }

    // -------------------------------------------------------------------------
    // Apply upsert — immutable-event union
    // -------------------------------------------------------------------------

    /**
     * Applies one upsert using the <strong>immutable-event union</strong> semantics:
     * <ul>
     *   <li>All 8 validation guards checked BEFORE any DB operation (database-reviewer mandate).</li>
     *   <li>Never-seen id → INSERT, {@code version:=1} → {@code applied[]}.</li>
     *   <li>Existing live id → idempotent no-op; echo current {@code version}/{@code updatedAt}
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
        // GUARD 1: Terminal-status guard — reject BEFORE DB op (database-reviewer)
        // Only status=completed is push-accepted (OQ-K1 RESOLVED / D2 / contract terminal-guard).
        // =================================================================
        String status = extractString(record, "status");
        if (!"completed".equals(status)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "non_terminal_status"));
        }

        // =================================================================
        // GUARD 2: startedAt — required, floating-civil YYYY-MM-DDTHH:mm (FLAG-1)
        // =================================================================
        String startedAtStr = extractString(record, "startedAt");
        LocalDateTime startedAt = parseCivil(startedAtStr);
        if (startedAt == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "started_at_required"));
        }

        // =================================================================
        // GUARD 3: endedAt — REQUIRED on completed (B1 / contract tightening).
        // absent → ended_before_started; present but < startedAt → ended_before_started.
        // =================================================================
        String endedAtStr = extractString(record, "endedAt");
        if (endedAtStr == null || endedAtStr.isBlank()) {
            // B1: endedAt required on completed
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "ended_before_started"));
        }
        LocalDateTime endedAt = parseCivil(endedAtStr);
        if (endedAt == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "ended_before_started"));
        }
        if (endedAt.isBefore(startedAt)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "ended_before_started"));
        }

        // =================================================================
        // GUARD 4+5: movementCount — required int ≥ 0 (G2 / contract tightening)
        // missing/malformed → movement_count_required; present but < 0 → movement_count_range
        // movementCount = 0 is VALID (INV-K2: finishing-before-target = completed)
        // =================================================================
        Object movementCountRaw = record.get("movementCount");
        if (movementCountRaw == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "movement_count_required"));
        }
        int movementCount;
        try {
            movementCount = ((Number) movementCountRaw).intValue();
        } catch (ClassCastException | NullPointerException e) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "movement_count_required"));
        }
        if (movementCount < 0) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "movement_count_range"));
        }

        // =================================================================
        // GUARD 6: targetCount — must equal 10 (MVP lock D5)
        // =================================================================
        Object targetCountRaw = record.get("targetCount");
        if (targetCountRaw == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "target_count_locked"));
        }
        int targetCount;
        try {
            targetCount = ((Number) targetCountRaw).intValue();
        } catch (ClassCastException | NullPointerException e) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "target_count_locked"));
        }
        if (targetCount != TARGET_COUNT_LOCKED) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "target_count_locked"));
        }

        // =================================================================
        // GUARD 7: durationSeconds — optional; if present must be ≥ 0
        // Server stores verbatim (DRIFT-1); only validates range if present.
        // =================================================================
        Object durationRaw = record.get("durationSeconds");
        Integer durationSeconds = null;
        if (durationRaw != null) {
            try {
                durationSeconds = ((Number) durationRaw).intValue();
            } catch (ClassCastException | NullPointerException e) {
                // malformed → treat as absent (no duration_range, just skip)
            }
            if (durationSeconds != null && durationSeconds < 0) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "validation_error", "duration_range"));
            }
        }

        // =================================================================
        // GUARD 8: note_cipher — optional; if present max size = 8192 bytes (OQ-K-B)
        // Client sends note as Base64-encoded ciphertext string.
        // Server NEVER parses note content; only checks encoded byte length.
        // =================================================================
        String noteBase64 = extractString(record, "note");
        byte[] noteCipher = null;
        if (noteBase64 != null && !noteBase64.isBlank()) {
            try {
                noteCipher = Base64.getDecoder().decode(noteBase64);
            } catch (IllegalArgumentException e) {
                // Not valid Base64 — treat as raw string bytes for length check
                noteCipher = noteBase64.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            if (noteCipher.length > MAX_NOTE_BYTES) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "validation_error", "note_too_large"));
            }
        }

        // =================================================================
        // All guards passed — apply immutable-event union semantics
        // =================================================================
        KickCountSession current = (KickCountSession) existing;

        if (current == null) {
            // Never-seen id → INSERT
            return insertNew(userId, id, startedAt, endedAt, movementCount, targetCount,
                    durationSeconds, noteCipher, record);
        }

        if (current.getDeletedAt() != null) {
            // Tombstone beats re-create (tombstone-wins)
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "tombstone_won", toRecord(current)));
        }

        // Existing live row → idempotent no-op (immutable event: never rewrite fields)
        // Echo current version/updatedAt; version is NOT bumped (contract §10 / D3)
        return new SyncApplyResult.Success(
                new Applied(COLLECTION, current.getId(),
                        current.getVersion() != null ? current.getVersion() : 1L,
                        current.getUpdatedAt()));
    }

    private SyncApplyResult insertNew(UUID userId, UUID id,
                                       LocalDateTime startedAt, LocalDateTime endedAt,
                                       int movementCount, int targetCount,
                                       Integer durationSeconds, byte[] noteCipher,
                                       Map<String, Object> record) {
        KickCountSession s = new KickCountSession();
        s.setId(id);
        s.setUserId(userId);
        s.setStartedAt(startedAt);
        s.setEndedAt(endedAt);
        s.setMovementCount(movementCount);
        s.setTargetCount(targetCount);
        s.setStatus("completed");
        s.setNoteCipher(noteCipher);

        // durationSeconds: verbatim (DRIFT-1); default 0 if absent
        s.setDurationSeconds(durationSeconds != null ? durationSeconds : 0);

        // gestationalWeekAtStart: verbatim (DRIFT-1 / D4); nullable-tolerant
        Object weekRaw = record.get("gestationalWeekAtStart");
        if (weekRaw != null) {
            try { s.setGestationalWeekAtStart(((Number) weekRaw).intValue()); }
            catch (Exception ignored) { /* nullable — store null if malformed */ }
        }

        // clientId
        UUID clientIdParsed = extractUUID(record, "clientId");
        s.setClientId(clientIdParsed);

        try {
            s = repository.saveAndFlush(s);
            // Contract §5: genuine create → version:=1 (Hibernate @Version seeds to 0)
            repository.initVersionToOne(s.getId());
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, s.getId(), 1L, s.getUpdatedAt()));
        } catch (Exception ex) {
            // Race: another thread/device concurrently inserted the same id for this user
            KickCountSession reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                // Immutable event no-op: echo reloaded state
                return new SyncApplyResult.Success(
                        new Applied(COLLECTION, reloaded.getId(),
                                reloaded.getVersion() != null ? reloaded.getVersion() : 1L,
                                reloaded.getUpdatedAt()));
            }
            // UUID collision with another user (IDOR attempt) → server_won with no record exposed
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", null));
        }
    }

    // -------------------------------------------------------------------------
    // Apply delete — tombstone-wins, unconditional; crypto-shred note_cipher
    // -------------------------------------------------------------------------

    /**
     * Tombstones the record unconditionally.
     *
     * <p>On tombstone: {@link KickCountSession#setNoteCipher(byte[])} is set to {@code null}
     * (crypto-shred — pdpa-assessment ruling 5a / K-2) so the tombstone row retains no
     * recoverable plaintext.
     *
     * <p>Never-seen id → inserts a tombstone skeleton so the deletion propagates to other
     * devices (OQ-SYNC-10).
     */
    @Override
    @Transactional
    public Applied applyDelete(UUID userId, UUID id, Object existing) {
        KickCountSession item = (KickCountSession) existing;

        if (item == null) {
            // Never-seen id → insert tombstone skeleton (OQ-SYNC-10)
            KickCountSession skeleton = new KickCountSession();
            skeleton.setId(id);
            skeleton.setUserId(userId);
            skeleton.setStartedAt(LocalDateTime.now().withSecond(0).withNano(0));
            skeleton.setEndedAt(LocalDateTime.now().withSecond(0).withNano(0));
            skeleton.setDurationSeconds(0);
            skeleton.setMovementCount(0);
            skeleton.setTargetCount(10);
            skeleton.setStatus("completed");
            skeleton.setDeletedAt(Instant.now());
            // noteCipher = null (nothing to shred for a skeleton)
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

        // Crypto-shred: zero out note_cipher BEFORE setting deletedAt
        // (pdpa-assessment ruling 5a — no plaintext residue in tombstone row)
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
        List<KickCountSession> rows = (cursorUpdatedAt == null)
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
     * Converts a {@link KickCountSession} to a serialisable {@link Map} for use in
     * pull {@code updated[]} and conflict {@code serverRecord}.
     *
     * <p>Note: {@code note_cipher} is echoed as a Base64 string under the key {@code "note"}
     * (the contract field name). The client decrypts it locally (INV-K1: server never parses).
     * When {@code noteCipher} is {@code null} (tombstone / no note), the key is omitted.
     */
    Map<String, Object> toRecord(KickCountSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("userId", s.getUserId());
        m.put("status", s.getStatus());
        m.put("startedAt", s.getStartedAt() != null ? s.getStartedAt().format(CIVIL_MINUTE_FMT) : null);
        m.put("endedAt", s.getEndedAt() != null ? s.getEndedAt().format(CIVIL_MINUTE_FMT) : null);
        m.put("durationSeconds", s.getDurationSeconds());
        m.put("movementCount", s.getMovementCount());
        m.put("targetCount", s.getTargetCount());
        m.put("gestationalWeekAtStart", s.getGestationalWeekAtStart());
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
        try {
            return LocalDateTime.parse(s, CIVIL_MINUTE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
