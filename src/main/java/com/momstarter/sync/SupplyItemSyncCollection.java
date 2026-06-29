package com.momstarter.sync;

import com.momstarter.sync.dto.Applied;
import com.momstarter.sync.dto.Conflict;
import com.momstarter.sync.dto.Rejected;
import com.momstarter.supply.SupplyItem;
import com.momstarter.supply.SupplyItemRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link SyncCollection} implementation for {@code supplyItems}.
 *
 * <p>First entity wired end-to-end with the offline-sync engine (OQ-SYNC-18).
 * Exercises the engine's core: optimistic {@code version}, {@code server_won}/{@code tombstone_won},
 * safe-window pull, cursor pagination, {@code (id,version)} de-dup.
 *
 * <h3>Consent gate</h3>
 * <p>{@code supplyItems} is NON-health (data-model §3.9 / api-contract "Consent gating"):
 * gated by {@code cloud_storage} (whole-batch) ONLY — no per-collection consent gate.
 * {@link #perCollectionConsentType()} returns {@code null}.
 *
 * <h3>Validation</h3>
 * <ul>
 *   <li>{@code name} must be non-null, non-blank (NOT NULL constraint).</li>
 *   <li>{@code category} must be one of {@code diapers|feeding|hygiene|health-supplies|other}.</li>
 *   <li>{@code onHandQty} is clamped to 0 server-side (never a validation_error — §A.7/E10).</li>
 *   <li>{@code lowNotifiedAtVersion} is an ordinary LWW field — server does NOT recompute it.</li>
 * </ul>
 *
 * <h3>Tombstone skeleton (OQ-SYNC-10)</h3>
 * <p>Deleting a never-seen id inserts a skeleton row ({@code name=""}, {@code category="other"},
 * {@code deleted_at=now()}) so the tombstone still converges to other devices.
 */
@Component
class SupplyItemSyncCollection implements SyncCollection {

    private static final String COLLECTION = "supplyItems";
    private static final Set<String> VALID_CATEGORIES = Set.of(
            "diapers", "feeding", "hygiene", "health-supplies", "other");

    private final SupplyItemRepository repository;

    SupplyItemSyncCollection(SupplyItemRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return COLLECTION;
    }

    /**
     * No per-collection consent gate — {@code supplyItems} rides {@code cloud_storage} only
     * (the whole-batch gate already checked in {@code SyncService.push}).
     */
    @Override
    public String perCollectionConsentType() {
        return null;
    }

    // -------------------------------------------------------------------------
    // Batch pre-load (push path)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Object> loadExisting(UUID userId, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return repository.findByUserIdAndIdIn(userId, ids)
                .stream()
                .collect(Collectors.toMap(SupplyItem::getId, item -> (Object) item));
    }

    // -------------------------------------------------------------------------
    // Apply upsert (optimistic CAS)
    // -------------------------------------------------------------------------

    /**
     * Applies one upsert record using the version-arbitrated LWW (S-A) conflict rule:
     * <ul>
     *   <li>No server row → INSERT ({@code version:=0} via Hibernate {@code @Version}).</li>
     *   <li>Server row tombstoned → {@code tombstone_won} conflict.</li>
     *   <li>{@code baseVersion == currentVersion} → UPDATE + bump version → {@code applied[]}.</li>
     *   <li>{@code baseVersion < currentVersion} → {@code server_won} conflict.</li>
     * </ul>
     *
     * <p>A mutable record with {@code base == current} ALWAYS applies and ALWAYS bumps version —
     * there is NO field-level no-op (api-contract §2 / OQ-SYNC-2). The server cannot detect a
     * field-level no-op (esp. for hypothetical future {@code *_cipher} fields).
     */
    @Override
    @Transactional
    public SyncApplyResult applyUpsert(UUID userId, Map<String, Object> record, Object existing) {
        UUID id = extractUUID(record, "id");
        if (id == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, null, "validation_error", "id is required"));
        }

        // Validate required fields
        String name = extractString(record, "name");
        String category = extractString(record, "category");

        if (name == null || name.isBlank()) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "name is required"));
        }
        if (category == null || !VALID_CATEGORIES.contains(category)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error",
                            "category must be one of: " + String.join(", ", VALID_CATEGORIES)));
        }

        // Base version from client (absent or 0 = create sentinel)
        long baseVersion = extractBaseVersion(record);

        SupplyItem current = (SupplyItem) existing;

        if (current == null) {
            // No server row → INSERT
            return insertNew(userId, id, record, name, category);
        }

        // Tombstone check (tombstone-wins is unconditional)
        if (current.getDeletedAt() != null) {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "tombstone_won", toRecord(current)));
        }

        // Version-arbitrated LWW (S-A)
        long currentVersion = current.getVersion() != null ? current.getVersion() : 0L;

        if (baseVersion == currentVersion) {
            // Match → apply (mutable: ALWAYS bump version — no no-op)
            return updateExisting(current, userId, id, record, name, category);
        } else {
            // base < current (or defensive: base > current → server_won)
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", toRecord(current)));
        }
    }

    private SyncApplyResult insertNew(UUID userId, UUID id, Map<String, Object> record,
                                       String name, String category) {
        SupplyItem item = new SupplyItem();
        item.setId(id);
        item.setUserId(userId);
        applyFields(item, record, name, category);
        try {
            item = repository.saveAndFlush(item); // Hibernate @Version seeds to 0 on INSERT
            // Contract §5 pin: genuine create → version:=1 (not 0).
            // initVersionToOne issues a JPQL UPDATE within the same transaction and clears the
            // L1 cache, so the DB and wire-visible applied[].version are both 1.
            repository.initVersionToOne(item.getId());
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, item.getId(), 1L, item.getUpdatedAt()));
        } catch (Exception ex) {
            // Race condition or UUID already taken (by this user or another).
            // Try to reload for the current user first (this-user concurrent insert).
            SupplyItem reloaded = null;
            try {
                reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                        .stream().findFirst().orElse(null);
            } catch (Exception reloadEx) {
                // Ignore reload failure — fall through to graceful conflict below
            }
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            // UUID exists for a different user (IDOR attempt) or session-cache collision.
            // Return server_won with no serverRecord (cannot expose another user's data).
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", null));
        }
    }

    private SyncApplyResult updateExisting(SupplyItem current, UUID userId, UUID id,
                                            Map<String, Object> record, String name, String category) {
        applyFields(current, record, name, category);
        try {
            current = repository.saveAndFlush(current);
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, current.getId(),
                            current.getVersion() != null ? current.getVersion() : 0L,
                            current.getUpdatedAt()));
        } catch (OptimisticLockingFailureException ex) {
            // Concurrent push won the race — reload and return server_won
            SupplyItem reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            throw ex;
        }
    }

    // -------------------------------------------------------------------------
    // Apply delete (tombstone-wins, unconditional)
    // -------------------------------------------------------------------------

    /**
     * Tombstones the record unconditionally. If the id has never been seen server-side,
     * inserts a tombstone skeleton so the deletion propagates to other devices (OQ-SYNC-10).
     *
     * <p>Delete of an already-tombstoned id is idempotent (returns current applied state).
     */
    @Override
    @Transactional
    public Applied applyDelete(UUID userId, UUID id, Object existing) {
        SupplyItem item = (SupplyItem) existing;

        if (item == null) {
            // Never-seen id → insert tombstone skeleton (OQ-SYNC-10)
            // Contract §5 pin: skeleton version:=1 (same as genuine create)
            SupplyItem skeleton = new SupplyItem();
            skeleton.setId(id);
            skeleton.setUserId(userId);
            skeleton.setName("");        // placeholder (NOT NULL satisfied)
            skeleton.setCategory("other");
            skeleton.setDeletedAt(Instant.now());
            skeleton = repository.saveAndFlush(skeleton); // Hibernate @Version seeds to 0
            repository.initVersionToOne(skeleton.getId()); // DB: version 0 → 1; L1 cache cleared
            return new Applied(COLLECTION, id, 1L, skeleton.getUpdatedAt());
        }

        if (item.getDeletedAt() != null) {
            // Already tombstoned → idempotent
            return new Applied(COLLECTION, id,
                    item.getVersion() != null ? item.getVersion() : 0L,
                    item.getUpdatedAt());
        }

        // Apply tombstone (version bumped by Hibernate @Version on update)
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
        List<SupplyItem> rows = (cursorUpdatedAt == null)
                ? repository.findForPull(userId, since, pageable)
                : repository.findForPullAfterCursor(userId, since, cursorUpdatedAt, cursorId, pageable);

        return rows.stream()
                .map(item -> new PullRecord(item.getId(), item.getUpdatedAt(), item.getDeletedAt(), toRecord(item)))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Field mapping helpers
    // -------------------------------------------------------------------------

    private void applyFields(SupplyItem item, Map<String, Object> record, String name, String category) {
        item.setName(name);
        item.setCategory(category);
        item.setUnit(extractString(record, "unit"));

        // onHandQty — clamp to 0, never negative (api-contract §A.7 / E10)
        int qty = extractInt(record, "onHandQty", 0);
        item.setOnHandQty(Math.max(0, qty));

        // lowThreshold — nullable int ≥ 0
        Object ltRaw = record.get("lowThreshold");
        if (ltRaw != null) {
            int lt = toInt(ltRaw, 0);
            item.setLowThreshold(Math.max(0, lt));
        } else {
            item.setLowThreshold(null);
        }

        // lowNotifiedAtVersion — ordinary LWW field, server does NOT recompute
        Object lnavRaw = record.get("lowNotifiedAtVersion");
        item.setLowNotifiedAtVersion(lnavRaw != null ? toInt(lnavRaw, 0) : null);

        // clientId — originating device UUID
        String clientIdStr = extractString(record, "clientId");
        if (clientIdStr != null) {
            try { item.setClientId(UUID.fromString(clientIdStr)); } catch (Exception ignored) {}
        }
    }

    /**
     * Converts a {@link SupplyItem} to a serialisable {@link Map} for use in
     * {@code serverRecord} (conflicts) and pull {@code updated[]} entries.
     */
    Map<String, Object> toRecord(SupplyItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("userId", item.getUserId());
        m.put("name", item.getName());
        m.put("category", item.getCategory());
        m.put("unit", item.getUnit());
        m.put("onHandQty", item.getOnHandQty());
        m.put("lowThreshold", item.getLowThreshold());
        m.put("lowNotifiedAtVersion", item.getLowNotifiedAtVersion());
        m.put("clientId", item.getClientId());
        m.put("version", item.getVersion() != null ? item.getVersion() : 0L);
        m.put("createdAt", item.getCreatedAt());
        m.put("updatedAt", item.getUpdatedAt());
        m.put("deletedAt", item.getDeletedAt());
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

    private static long extractBaseVersion(Map<String, Object> record) {
        Object val = record.get("version");
        if (val == null) return 0L;
        return toLong(val, 0L);
    }

    private static int extractInt(Map<String, Object> record, String key, int defaultValue) {
        Object val = record.get(key);
        if (val == null) return defaultValue;
        return toInt(val, defaultValue);
    }

    private static int toInt(Object val, int defaultValue) {
        try { return ((Number) val).intValue(); } catch (Exception e) { return defaultValue; }
    }

    private static long toLong(Object val, long defaultValue) {
        try { return ((Number) val).longValue(); } catch (Exception e) { return defaultValue; }
    }
}
