package com.momstarter.sync;

import com.momstarter.medication.MedicationLog;
import com.momstarter.medication.MedicationLogRepository;
import com.momstarter.medication.MedicationPlan;
import com.momstarter.medication.MedicationPlanRepository;
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
 * {@link SyncCollection} implementation for the {@code medicationLogs} collection.
 *
 * <h3>Record class: IMMUTABLE event, create-only union (spec D3 / RULING 5)</h3>
 * <p>Mirrors {@link SelfLogSyncCollection}. Each log is a distinct client UUIDv4 — union
 * across devices with no LWW conflict. Re-push of the same id is an idempotent no-op (echo
 * current version/updatedAt, version NOT bumped, no overwrite). Updates are not accepted;
 * a correction is a NEW row (new UUID) and/or a tombstone of the old one.
 *
 * <h3>loggedAt — server-assigned absolute-UTC instant (D5)</h3>
 * <p>{@code loggedAt} is NOT in {@code MedicationLogInput} and is NOT read from the push
 * payload. It is assigned by {@link MedicationLog#onCreate()} (server clock authority).
 *
 * <h3>Ownership check — medicationPlanId (G-4 / D7)</h3>
 * <p>If {@code medicationPlanId} is present, the apply path verifies it references a
 * <strong>live</strong> {@code medication_plan} row owned by the subject. The hard DB FK
 * ({@code medication_plan_id → medication_plan(id)}) checks existence only, not ownership
 * or liveness → apply-path mismatch yields
 * {@code validation_error(medication_plan_not_found)}. {@code null} medicationPlanId is
 * legal (ad-hoc dose, E6).
 *
 * <h3>No server-side dedup (spec §B.5)</h3>
 * <p>Two distinct-UUID logs with the same (medicationPlanId, civil-day) legitimately persist
 * (E8, AC-22). The {@code (medicationPlanId, civil-day)} dedup is a CLIENT render-time and
 * adherence-count concern, NOT a server uniqueness constraint (§A.1 pinned).
 *
 * <h3>Crypto-shred on tombstone (§4.4(A) / RULING 1)</h3>
 * <p>On soft-delete, {@code note_cipher} is set to {@code null}. Mirrors the
 * {@code SelfLogSyncCollection} shred behaviour.
 *
 * <h3>Per-collection consent gate (D6)</h3>
 * <p>MOTHER-health collection (SD-2) → gated by {@code general_health}. Absent consent
 * yields {@code rejected[]{collection:"medicationLogs", code:"consent_required",
 * details:"general_health"}} with id omitted; rest of batch applies (contract :349).
 *
 * <h3>Validation — closed sub-code enum (RULING 3)</h3>
 * <p>{@code status_invalid} · {@code occurrence_time_required} · {@code occurrence_time_malformed}
 * · {@code note_too_large} · {@code medication_plan_not_found}
 */
@Component
class MedicationLogSyncCollection implements SyncCollection {

    private static final String COLLECTION = "medicationLogs";

    /** Valid status values (DB CHECK backstop: {@code CHECK (status IN ('taken','missed'))}). */
    private static final Set<String> VALID_STATUSES = Set.of("taken", "missed");

    /** Maximum decoded byte size for note_cipher (same cap as the other *_cipher note family). */
    private static final int MAX_NOTE_BYTES = 8192;

    /**
     * Floating-civil minute-precision formatter (FLAG-1 / D5).
     * {@code occurrenceTime} uses this format — the calendar/adherence bucket key.
     */
    private static final DateTimeFormatter CIVIL_MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final MedicationLogRepository logRepository;
    private final MedicationPlanRepository planRepository;

    MedicationLogSyncCollection(MedicationLogRepository logRepository,
                                 MedicationPlanRepository planRepository) {
        this.logRepository = logRepository;
        this.planRepository = planRepository;
    }

    @Override
    public String name() { return COLLECTION; }

    /**
     * MOTHER-health collection (SD-2) — gated by {@code general_health}.
     * Fail-closed: absent consent → per-collection reject, id omitted, rest of batch applies.
     */
    @Override
    public String perCollectionConsentType() { return "general_health"; }

    // -------------------------------------------------------------------------
    // Batch pre-load
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Object> loadExisting(UUID userId, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return logRepository.findByUserIdAndIdIn(userId, ids)
                .stream()
                .collect(Collectors.toMap(MedicationLog::getId, l -> (Object) l));
    }

    // -------------------------------------------------------------------------
    // Apply upsert — immutable-event union (D3)
    // -------------------------------------------------------------------------

    /**
     * Applies one upsert using the <strong>immutable-event union</strong> semantics:
     * <ul>
     *   <li>All validation guards checked BEFORE any DB operation (first-fail-wins).</li>
     *   <li>Never-seen id → INSERT; {@code loggedAt} = server clock ({@code now()});
     *       {@code version:=1} → {@code applied[]}.</li>
     *   <li>Existing live id → idempotent no-op; echo current version/updatedAt;
     *       version NOT bumped (D3 — immutable event, no overwrite).</li>
     *   <li>Existing tombstoned id → {@code tombstone_won} conflict.</li>
     * </ul>
     *
     * <h3>Guard order (first-fail-wins)</h3>
     * <ol>
     *   <li>id absent → validation_error</li>
     *   <li>status absent or ∉ {taken, missed} → status_invalid</li>
     *   <li>occurrenceTime absent → occurrence_time_required</li>
     *   <li>occurrenceTime malformed → occurrence_time_malformed</li>
     *   <li>note decoded size &gt; 8 KB → note_too_large</li>
     *   <li>medicationPlanId present but not a live plan owned by subject →
     *       medication_plan_not_found (G-4 / D7)</li>
     * </ol>
     */
    @Override
    @Transactional
    public SyncApplyResult applyUpsert(UUID userId, Map<String, Object> record, Object existing) {

        // --- Extract id (needed for all rejection reports) ---
        UUID id = extractUUID(record, "id");
        if (id == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, null, "validation_error", "id is required"));
        }

        // =================================================================
        // GUARD 1: status — required, must be "taken" or "missed"
        // Missing folds into status_invalid (RULING 3 — no separate "status_required")
        // =================================================================
        String status = extractString(record, "status");
        if (status == null || !VALID_STATUSES.contains(status)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "status_invalid"));
        }

        // =================================================================
        // GUARD 2: occurrenceTime — required, floating-civil YYYY-MM-DDTHH:mm (FLAG-1 / D5)
        // =================================================================
        String occurrenceTimeStr = extractString(record, "occurrenceTime");
        if (occurrenceTimeStr == null || occurrenceTimeStr.isBlank()) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "occurrence_time_required"));
        }
        LocalDateTime occurrenceTime = parseCivil(occurrenceTimeStr);
        if (occurrenceTime == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "occurrence_time_malformed"));
        }

        // =================================================================
        // GUARD 3: note_cipher — optional; if present max decoded size = 8192 bytes
        // Server NEVER parses note content (INV-M3 / G4 / ADR RULING 1).
        // =================================================================
        byte[] noteCipher = decodeBase64Optional(record, "note");
        if (noteCipher != null && noteCipher.length > MAX_NOTE_BYTES) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "note_too_large"));
        }

        // =================================================================
        // GUARD 4: medicationPlanId ownership (G-4 / D7)
        // If present: must reference a LIVE medication_plan row owned by the subject.
        // DB FK checks existence only, not ownership or liveness → apply-path check required.
        // null medicationPlanId = ad-hoc dose (E6) = legal, skip check.
        // =================================================================
        UUID medicationPlanId = extractUUID(record, "medicationPlanId");
        if (medicationPlanId != null) {
            MedicationPlan plan = planRepository.findByUserIdAndIdIn(userId, Set.of(medicationPlanId))
                    .stream().findFirst().orElse(null);
            if (plan == null || plan.getDeletedAt() != null) {
                // Not found, not owned, or tombstoned → reject
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "validation_error",
                                "medication_plan_not_found"));
            }
        }

        // Optional clientId
        UUID clientId = extractUUID(record, "clientId");

        // =================================================================
        // All guards passed — apply immutable-event union semantics
        // =================================================================
        MedicationLog current = (MedicationLog) existing;

        if (current == null) {
            // Never-seen id → INSERT
            return insertNew(userId, id, status, occurrenceTime, noteCipher,
                    medicationPlanId, clientId);
        }

        if (current.getDeletedAt() != null) {
            // Tombstone beats re-create (tombstone-wins)
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "tombstone_won", toRecord(current)));
        }

        // Existing live row → idempotent no-op (immutable event: never rewrite fields, D3)
        // Echo current version/updatedAt; version NOT bumped
        return new SyncApplyResult.Success(
                new Applied(COLLECTION, current.getId(),
                        current.getVersion() != null ? current.getVersion() : 1L,
                        current.getUpdatedAt()));
    }

    private SyncApplyResult insertNew(UUID userId, UUID id, String status,
                                       LocalDateTime occurrenceTime, byte[] noteCipher,
                                       UUID medicationPlanId, UUID clientId) {
        MedicationLog l = new MedicationLog();
        l.setId(id);
        l.setUserId(userId);
        l.setStatus(status);
        l.setOccurrenceTime(occurrenceTime);
        l.setNoteCipher(noteCipher);
        l.setMedicationPlanId(medicationPlanId);
        l.setClientId(clientId);
        // loggedAt: assigned server-side in MedicationLog.onCreate() (D5 — NOT from client)

        try {
            l = logRepository.saveAndFlush(l);
            // Contract §5: genuine create → version:=1 (Hibernate @Version seeds to 0)
            logRepository.initVersionToOne(l.getId());
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, l.getId(), 1L, l.getUpdatedAt()));
        } catch (Exception ex) {
            // Race: concurrent insert of the same id (another device pushed same UUIDv4)
            MedicationLog reloaded = logRepository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                // Immutable event no-op: echo reloaded state (same id = same event)
                return new SyncApplyResult.Success(
                        new Applied(COLLECTION, reloaded.getId(),
                                reloaded.getVersion() != null ? reloaded.getVersion() : 1L,
                                reloaded.getUpdatedAt()));
            }
            // UUID collision with another user (IDOR attempt) — server_won, no record exposed
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", null));
        }
    }

    // -------------------------------------------------------------------------
    // Apply delete — tombstone-wins, unconditional; crypto-shred note_cipher
    // -------------------------------------------------------------------------

    /**
     * Tombstones the log unconditionally.
     *
     * <p>Crypto-shred: sets {@code note_cipher} to {@code null} BEFORE setting
     * {@code deleted_at} (PDPA §4.4(A) / ADR RULING 1 — SD-2 health data).
     *
     * <p>Never-seen id → inserts a tombstone skeleton so the deletion propagates to
     * other devices (OQ-SYNC-10).
     */
    @Override
    @Transactional
    public Applied applyDelete(UUID userId, UUID id, Object existing) {
        MedicationLog item = (MedicationLog) existing;

        if (item == null) {
            // Never-seen id → insert tombstone skeleton (OQ-SYNC-10)
            MedicationLog skeleton = new MedicationLog();
            skeleton.setId(id);
            skeleton.setUserId(userId);
            skeleton.setStatus("taken");   // placeholder — NOT NULL + DB CHECK
            skeleton.setOccurrenceTime(LocalDateTime.now().withSecond(0).withNano(0)); // placeholder
            // note_cipher: null (nothing to shred for a skeleton)
            skeleton.setDeletedAt(Instant.now());
            skeleton = logRepository.saveAndFlush(skeleton);
            logRepository.initVersionToOne(skeleton.getId());
            return new Applied(COLLECTION, id, 1L, skeleton.getUpdatedAt());
        }

        if (item.getDeletedAt() != null) {
            // Already tombstoned → idempotent
            return new Applied(COLLECTION, id,
                    item.getVersion() != null ? item.getVersion() : 0L,
                    item.getUpdatedAt());
        }

        // Crypto-shred: set note_cipher to null BEFORE setting deleted_at
        // (PDPA §4.4(A) / RULING 1 — no plaintext residue in tombstone row)
        item.setNoteCipher(null);
        item.setDeletedAt(Instant.now());
        item = logRepository.saveAndFlush(item);
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
        List<MedicationLog> rows = (cursorUpdatedAt == null)
                ? logRepository.findForPull(userId, since, pageable)
                : logRepository.findForPullAfterCursor(userId, since, cursorUpdatedAt, cursorId, pageable);
        return rows.stream()
                .map(l -> new PullRecord(l.getId(), l.getUpdatedAt(), l.getDeletedAt(), toRecord(l)))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Field mapping
    // -------------------------------------------------------------------------

    /**
     * Converts a {@link MedicationLog} to a serialisable {@link Map} for pull
     * {@code updated[]} and conflict {@code serverRecord}.
     *
     * <p>{@code note_cipher}: echoed as Base64 string if non-null; omitted when null
     * (tombstone crypto-shred or absent note). Server NEVER decrypts (INV-M3 / G4).
     *
     * <p>{@code loggedAt}: absolute-UTC server-assigned instant; echoed verbatim (D5).
     *
     * <p>{@code occurrenceTime}: floating-civil; formatted as {@code "YYYY-MM-DDTHH:mm"} (FLAG-1).
     */
    Map<String, Object> toRecord(MedicationLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("userId", l.getUserId());
        m.put("medicationPlanId", l.getMedicationPlanId());
        m.put("occurrenceTime", l.getOccurrenceTime() != null
                ? l.getOccurrenceTime().format(CIVIL_MINUTE_FMT) : null);
        m.put("status", l.getStatus());
        m.put("loggedAt", l.getLoggedAt());
        // note_cipher: echoed as Base64; omitted if null (tombstone or no note)
        if (l.getNoteCipher() != null) {
            m.put("note", Base64.getEncoder().encodeToString(l.getNoteCipher()));
        }
        m.put("clientId", l.getClientId());
        m.put("version", l.getVersion() != null ? l.getVersion() : 0L);
        m.put("createdAt", l.getCreatedAt());
        m.put("updatedAt", l.getUpdatedAt());
        m.put("deletedAt", l.getDeletedAt());
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
     * Decodes a Base64-encoded note field from the push record to {@code byte[]}.
     * Returns {@code null} if absent, null, or blank (optional field).
     * Never parses the content (INV-M3 / G4 — opaque ciphertext).
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
}
