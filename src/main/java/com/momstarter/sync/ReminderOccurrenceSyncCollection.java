package com.momstarter.sync;

import com.momstarter.occurrence.OccurrenceId;
import com.momstarter.occurrence.StatusMerge;
import com.momstarter.reminder.ReminderOccurrence;
import com.momstarter.reminder.ReminderOccurrenceRepository;
import com.momstarter.sync.dto.Applied;
import com.momstarter.sync.dto.Conflict;
import com.momstarter.sync.dto.Rejected;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link SyncCollection} implementation for the {@code reminderOccurrences} collection.
 *
 * <h3>Sparse table (W-A, OQ-CAL-4)</h3>
 * <p>Only terminal user-action rows ({@code done}/{@code snoozed}) are pushed.
 * A projected {@code due} instance is NEVER pushed; a {@code missed} status is
 * derived on-device and is NOT pushed in MVP. The apply path rejects both.
 *
 * <h3>Deterministic id (N6/N7)</h3>
 * <p>The server recomputes {@code id = uuidv5(OCCURRENCE_NAMESPACE, lower(reminderId) + "|" +
 * scheduledLocalTime)} and rejects any record whose supplied {@code id} does not match.
 * See {@link OccurrenceId#compute}.
 *
 * <h3>M1 precedence merge (US-15 AC#4)</h3>
 * <p>Explicit {@code done}/{@code snoozed} always outranks a derived {@code missed} for the
 * same id, regardless of version ordering. This is DEFENSIVE for MVP (MVP clients never push
 * {@code missed}), implemented via {@link StatusMerge}.
 *
 * <h3>Per-collection consent gate</h3>
 * <p>MOTHER-health collection → gated by {@code general_health}.
 */
@Component
class ReminderOccurrenceSyncCollection implements SyncCollection {

    private static final String COLLECTION = "reminderOccurrences";

    /** Statuses that a client MAY push (terminal user actions). */
    private static final Set<String> PUSHABLE_STATUSES = Set.of("done", "snoozed");

    /** Statuses that represent a "handled" outcome for M1 precedence. */
    private static final Set<String> HANDLED = Set.of("done", "snoozed");

    /** Floating-civil minute-precision formatter. */
    private static final DateTimeFormatter CIVIL_MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final ReminderOccurrenceRepository repository;

    ReminderOccurrenceSyncCollection(ReminderOccurrenceRepository repository) {
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
                .collect(Collectors.toMap(ReminderOccurrence::getId, o -> (Object) o));
    }

    // -------------------------------------------------------------------------
    // Apply upsert
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public SyncApplyResult applyUpsert(UUID userId, Map<String, Object> record, Object existing) {
        UUID suppliedId = extractUUID(record, "id");
        if (suppliedId == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, null, "validation_error", "id is required"));
        }

        // (c) Reject non-terminal status (FLAG-7 / W-A, OQ-CAL-4)
        String status = extractString(record, "status");
        if (status == null || !PUSHABLE_STATUSES.contains(status)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, suppliedId, "validation_error", "non_terminal_status"));
        }

        // Validate reminderId
        String reminderIdStr = extractString(record, "reminderId");
        if (reminderIdStr == null || reminderIdStr.isBlank()) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, suppliedId, "validation_error", "reminderId is required"));
        }
        // (a) Normalize reminderId to canonical-lowercase (🟡-3)
        String lowerReminderId = reminderIdStr.toLowerCase();
        UUID reminderId;
        try { reminderId = UUID.fromString(lowerReminderId); }
        catch (IllegalArgumentException e) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, suppliedId, "validation_error", "reminderId must be a UUID"));
        }

        // (b) Validate scheduledLocalTime — minute-precision floating civil "YYYY-MM-DDTHH:mm"
        String scheduledStr = extractString(record, "scheduledLocalTime");
        LocalDateTime scheduledLocalTime = parseCivilMinute(scheduledStr);
        if (scheduledLocalTime == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, suppliedId, "validation_error",
                            "scheduledLocalTime must be YYYY-MM-DDTHH:mm (minute precision)"));
        }
        // Second must be 0 (minute precision)
        if (scheduledLocalTime.getSecond() != 0 || scheduledLocalTime.getNano() != 0) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, suppliedId, "validation_error",
                            "scheduledLocalTime must be minute-precision (no seconds)"));
        }

        // (a) Server recomputes expected id = uuidv5(NAMESPACE, lower(reminderId) + "|" + scheduledLocalTime)
        // The scheduledLocalTime string is the exact byte string used in the hash.
        // Format to canonical "YYYY-MM-DDTHH:mm" to ensure byte-identical hash input.
        String civilStr = scheduledLocalTime.format(CIVIL_MINUTE_FMT);
        UUID expectedId = OccurrenceId.compute(lowerReminderId, civilStr);

        if (!expectedId.equals(suppliedId)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, suppliedId, "validation_error",
                            "id does not match uuidv5(reminderId|scheduledLocalTime)"));
        }

        long baseVersion = extractBaseVersion(record);
        ReminderOccurrence current = (ReminderOccurrence) existing;

        if (current == null) {
            return insertNew(userId, suppliedId, reminderId, scheduledLocalTime, civilStr,
                    status, record);
        }

        if (current.getDeletedAt() != null) {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, suppliedId, "tombstone_won", toRecord(current)));
        }

        long currentVersion = current.getVersion() != null ? current.getVersion() : 0L;

        if (baseVersion == currentVersion) {
            // Base == current → apply (mutable always bumps version)
            return updateExisting(current, userId, suppliedId, status, record);
        } else {
            // base < current — check M1 override BEFORE returning server_won
            // (d) M1 precedence: incoming done/snoozed beats existing missed, regardless of version
            if (HANDLED.contains(status) && "missed".equals(current.getStatus())) {
                return updateExisting(current, userId, suppliedId, status, record);
            }
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, suppliedId, "server_won", toRecord(current)));
        }
    }

    private SyncApplyResult insertNew(UUID userId, UUID id, UUID reminderId,
                                       LocalDateTime scheduledLocalTime, String civilStr,
                                       String status, Map<String, Object> record) {
        ReminderOccurrence o = new ReminderOccurrence();
        o.setId(id);
        o.setUserId(userId);
        o.setReminderId(reminderId);  // stored as lowercase UUID (already normalised)
        o.setScheduledLocalTime(scheduledLocalTime);
        applyStatusFields(o, status, record);
        try {
            o = repository.saveAndFlush(o);
            repository.initVersionToOne(o.getId());
            return new SyncApplyResult.Success(new Applied(COLLECTION, o.getId(), 1L, o.getUpdatedAt()));
        } catch (Exception ex) {
            ReminderOccurrence reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", null));
        }
    }

    private SyncApplyResult updateExisting(ReminderOccurrence current, UUID userId, UUID id,
                                            String status, Map<String, Object> record) {
        // M1 merge: use StatusMerge to determine final status
        // (In the base==current case both HANDLED; in M1 override, incoming beats missed)
        String finalStatus = StatusMerge.merge(
                current.getStatus(), current.getUpdatedAt(),
                status, Instant.now());
        applyStatusFields(current, finalStatus, record);
        try {
            current = repository.saveAndFlush(current);
            return new SyncApplyResult.Success(new Applied(COLLECTION, current.getId(),
                    current.getVersion() != null ? current.getVersion() : 0L,
                    current.getUpdatedAt()));
        } catch (OptimisticLockingFailureException ex) {
            ReminderOccurrence reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            throw ex;
        }
    }

    private void applyStatusFields(ReminderOccurrence o, String status, Map<String, Object> record) {
        o.setStatus(status);

        // actedAt — absolute UTC instant of the done/snoozed action
        String actedAtStr = extractString(record, "actedAt");
        if (actedAtStr != null) {
            try { o.setActedAt(Instant.parse(actedAtStr)); } catch (Exception ignored) {}
        }

        // snoozedUntil — absolute UTC re-fire instant for snoozed occurrences
        String snoozedUntilStr = extractString(record, "snoozedUntil");
        if (snoozedUntilStr != null) {
            try { o.setSnoozedUntil(Instant.parse(snoozedUntilStr)); } catch (Exception ignored) {}
        }

        // clientId
        String clientIdStr = extractString(record, "clientId");
        if (clientIdStr != null) {
            try { o.setClientId(UUID.fromString(clientIdStr)); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Apply delete (tombstone-wins, unconditional)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public Applied applyDelete(UUID userId, UUID id, Object existing) {
        ReminderOccurrence item = (ReminderOccurrence) existing;

        if (item == null) {
            // Never-seen id → insert tombstone skeleton (OQ-SYNC-10)
            // Use a sentinel reminderId (no real parent required — soft link)
            ReminderOccurrence skeleton = new ReminderOccurrence();
            skeleton.setId(id);
            skeleton.setUserId(userId);
            skeleton.setReminderId(id); // dummy soft link: reminderId = id itself
            skeleton.setScheduledLocalTime(LocalDateTime.now().withSecond(0).withNano(0));
            skeleton.setStatus("done");
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
        List<ReminderOccurrence> rows = (cursorUpdatedAt == null)
                ? repository.findForPull(userId, since, pageable)
                : repository.findForPullAfterCursor(userId, since, cursorUpdatedAt, cursorId, pageable);
        return rows.stream()
                .map(o -> new PullRecord(o.getId(), o.getUpdatedAt(), o.getDeletedAt(), toRecord(o)))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Field mapping
    // -------------------------------------------------------------------------

    Map<String, Object> toRecord(ReminderOccurrence o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("userId", o.getUserId());
        m.put("reminderId", o.getReminderId());
        m.put("scheduledLocalTime",
                o.getScheduledLocalTime() != null ? o.getScheduledLocalTime().format(CIVIL_MINUTE_FMT) : null);
        m.put("status", o.getStatus());
        m.put("actedAt", o.getActedAt());
        m.put("snoozedUntil", o.getSnoozedUntil());
        m.put("clientId", o.getClientId());
        m.put("version", o.getVersion() != null ? o.getVersion() : 0L);
        m.put("createdAt", o.getCreatedAt());
        m.put("updatedAt", o.getUpdatedAt());
        m.put("deletedAt", o.getDeletedAt());
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

    private static long extractBaseVersion(Map<String, Object> record) {
        Object val = record.get("version");
        if (val == null) return 0L;
        try { return ((Number) val).longValue(); } catch (Exception e) { return 0L; }
    }

    private static LocalDateTime parseCivilMinute(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s, CIVIL_MINUTE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
