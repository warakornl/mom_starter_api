package com.momstarter.sync;

import com.momstarter.checklist.ChecklistItem;
import com.momstarter.checklist.ChecklistItemRepository;
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
 * {@link SyncCollection} implementation for the {@code checklistItems} collection.
 *
 * <h3>Record class</h3>
 * <p>Mutable → LWW on server {@code updated_at} + optimistic {@code version}
 * (identical pattern to {@code supplyItems} / {@code reminders}).
 *
 * <h3>Per-collection consent gate</h3>
 * <p>MOTHER-health collection → gated by {@code general_health}.
 *
 * <h3>Appointment rules (OQ-CAL-1/2 R-A — CLIENT-ONLY)</h3>
 * <p>Location/doctor/clinic/phone are folded into free-text {@code note} (OQ-CAL-1 R-A).
 * {@code appointment}/{@code anc_visit} items MUST be dated — but this is a CLIENT rule
 * (the server stores {@code scheduledAt} verbatim; no DB-level per-category NOT NULL).
 * No per-category validation here.
 */
@Component
class ChecklistItemSyncCollection implements SyncCollection {

    private static final String COLLECTION = "checklistItems";

    /** Valid category values per data-model §3.4. */
    private static final Set<String> VALID_CATEGORIES = Set.of(
            "appointment", "anc_visit", "lab_panel", "screening",
            "vaccine", "checklist_task", "postpartum_check");

    /** Valid source values. */
    private static final Set<String> VALID_SOURCES = Set.of("user_created", "from_suggestion");

    /** Floating-civil formatter for {@code scheduledAt}. */
    private static final DateTimeFormatter CIVIL_MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final ChecklistItemRepository repository;

    ChecklistItemSyncCollection(ChecklistItemRepository repository) {
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
                .collect(Collectors.toMap(ChecklistItem::getId, c -> (Object) c));
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

        String title = extractString(record, "title");
        String category = extractString(record, "category");

        if (title == null || title.isBlank()) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "title is required"));
        }
        if (category == null || !VALID_CATEGORIES.contains(category)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error",
                            "category must be one of: " + String.join(", ", VALID_CATEGORIES)));
        }

        long baseVersion = extractBaseVersion(record);
        ChecklistItem current = (ChecklistItem) existing;

        if (current == null) {
            return insertNew(userId, id, record, title, category);
        }

        if (current.getDeletedAt() != null) {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "tombstone_won", toRecord(current)));
        }

        long currentVersion = current.getVersion() != null ? current.getVersion() : 0L;
        if (baseVersion == currentVersion) {
            return updateExisting(current, userId, id, record, title, category);
        } else {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", toRecord(current)));
        }
    }

    private SyncApplyResult insertNew(UUID userId, UUID id, Map<String, Object> record,
                                       String title, String category) {
        ChecklistItem item = new ChecklistItem();
        item.setId(id);
        item.setUserId(userId);
        applyFields(item, record, title, category);
        try {
            item = repository.saveAndFlush(item);
            repository.initVersionToOne(item.getId());
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, item.getId(), 1L, item.getUpdatedAt()));
        } catch (Exception ex) {
            ChecklistItem reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", null));
        }
    }

    private SyncApplyResult updateExisting(ChecklistItem current, UUID userId, UUID id,
                                            Map<String, Object> record, String title,
                                            String category) {
        applyFields(current, record, title, category);
        try {
            current = repository.saveAndFlush(current);
            return new SyncApplyResult.Success(new Applied(COLLECTION, current.getId(),
                    current.getVersion() != null ? current.getVersion() : 0L,
                    current.getUpdatedAt()));
        } catch (OptimisticLockingFailureException ex) {
            ChecklistItem reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
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
        ChecklistItem item = (ChecklistItem) existing;

        if (item == null) {
            ChecklistItem skeleton = new ChecklistItem();
            skeleton.setId(id);
            skeleton.setUserId(userId);
            skeleton.setTitle("");
            skeleton.setCategory("checklist_task");
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
        List<ChecklistItem> rows = (cursorUpdatedAt == null)
                ? repository.findForPull(userId, since, pageable)
                : repository.findForPullAfterCursor(userId, since, cursorUpdatedAt, cursorId, pageable);
        return rows.stream()
                .map(c -> new PullRecord(c.getId(), c.getUpdatedAt(), c.getDeletedAt(), toRecord(c)))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Field mapping
    // -------------------------------------------------------------------------

    private void applyFields(ChecklistItem item, Map<String, Object> record,
                              String title, String category) {
        item.setTitle(title);
        item.setCategory(category);

        // scheduledAt — nullable floating-civil bucket key (FLAG-1)
        String scheduledAtStr = extractString(record, "scheduledAt");
        item.setScheduledAt(parseCivilMinute(scheduledAtStr));

        // done + doneAt
        Object doneRaw = record.get("done");
        boolean done = doneRaw != null && (Boolean.TRUE.equals(doneRaw)
                || "true".equalsIgnoreCase(doneRaw.toString()));
        item.setDone(done);
        if (done && item.getDoneAt() == null) {
            // Set doneAt on first marking done; absolute UTC
            String doneAtStr = extractString(record, "doneAt");
            if (doneAtStr != null) {
                try { item.setDoneAt(Instant.parse(doneAtStr)); } catch (Exception ignored) {}
            } else {
                item.setDoneAt(Instant.now());
            }
        } else if (!done) {
            item.setDoneAt(null);
        }

        // note — free-text, never parsed (G4)
        item.setNote(extractString(record, "note"));

        // source
        String source = extractString(record, "source");
        item.setSource(source != null && VALID_SOURCES.contains(source) ? source : "user_created");

        // sourceSuggestionStateId — nullable soft link
        UUID ssid = extractUUID(record, "sourceSuggestionStateId");
        item.setSourceSuggestionStateId(ssid);

        // clientId
        String clientIdStr = extractString(record, "clientId");
        if (clientIdStr != null) {
            try { item.setClientId(UUID.fromString(clientIdStr)); } catch (Exception ignored) {}
        }
    }

    Map<String, Object> toRecord(ChecklistItem c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("userId", c.getUserId());
        m.put("category", c.getCategory());
        m.put("title", c.getTitle());
        m.put("scheduledAt", c.getScheduledAt() != null ? c.getScheduledAt().format(CIVIL_MINUTE_FMT) : null);
        m.put("done", c.isDone());
        m.put("doneAt", c.getDoneAt());
        m.put("note", c.getNote());
        m.put("source", c.getSource());
        m.put("sourceSuggestionStateId", c.getSourceSuggestionStateId());
        m.put("clientId", c.getClientId());
        m.put("version", c.getVersion() != null ? c.getVersion() : 0L);
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        m.put("deletedAt", c.getDeletedAt());
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
        try { return LocalDateTime.parse(s, CIVIL_MINUTE_FMT); }
        catch (DateTimeParseException e) { return null; }
    }
}
