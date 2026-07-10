package com.momstarter.sync;

import com.momstarter.consumption.ConsumptionMapping;
import com.momstarter.consumption.ConsumptionMappingRepository;
import com.momstarter.pregnancy.ConsentChecker;
import com.momstarter.sync.dto.Applied;
import com.momstarter.sync.dto.Conflict;
import com.momstarter.sync.dto.Rejected;
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
 * {@link SyncCollection} implementation for the {@code consumptionMappings} collection.
 *
 * <h3>PER-ROW consent gate (INV-ASD-9)</h3>
 * <p>{@link #perCollectionConsentType()} returns {@code null} (no collection-level health gate).
 * Each row is gated in {@link #applyUpsert} based on its {@code activityType}:
 * <ul>
 *   <li>{@code feeding_formula} → {@code infant_feeding} + {@code general_health} (dual)</li>
 *   <li>{@code diaper_change} / {@code bathing} → {@code general_health} only</li>
 * </ul>
 * A missing consent returns {@link SyncApplyResult.RejectedResult} with {@code consent_required}.
 *
 * <h3>Delete is unconditional (tombstone-wins)</h3>
 * <p>{@link #applyDelete} is unconditional — consent is NOT checked for deletes.
 * Tombstone propagation must not be blocked by consent state (sync spec §A.5).
 *
 * <h3>Record class: mutable LWW</h3>
 * <p>Same as SupplyItem / Reminder. Version-arbitrated LWW (S-A conflict rule).
 *
 * <h3>No crypto-shred</h3>
 * <p>No {@code *_cipher} columns; no per-row shred step needed on tombstone.
 * Rows contain health correlate data and are purged by TIER1 and 180-day GC.
 */
@Component
class ConsumptionMappingSyncCollection implements SyncCollection {

    private static final String COLLECTION = "consumptionMappings";
    private static final Set<String> VALID_ACTIVITY_TYPES =
            Set.of("feeding_formula", "diaper_change", "bathing");

    private final ConsumptionMappingRepository repository;
    private final ConsentChecker consentChecker;

    ConsumptionMappingSyncCollection(ConsumptionMappingRepository repository,
                                      ConsentChecker consentChecker) {
        this.repository = repository;
        this.consentChecker = consentChecker;
    }

    @Override
    public String name() { return COLLECTION; }

    /**
     * No collection-level health gate — consent varies per row (per-row check in
     * {@link #applyUpsert}).
     */
    @Override
    public String perCollectionConsentType() { return null; }

    // -------------------------------------------------------------------------
    // Batch pre-load
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Object> loadExisting(UUID userId, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return repository.findByUserIdAndIdIn(userId, ids)
                .stream()
                .collect(Collectors.toMap(ConsumptionMapping::getId, m -> (Object) m));
    }

    // -------------------------------------------------------------------------
    // Apply upsert — mutable LWW with per-row consent gate
    // -------------------------------------------------------------------------

    /**
     * Applies one upsert using version-arbitrated LWW (S-A) with per-row consent gating.
     *
     * <p>Per-row consent is checked BEFORE any DB operation:
     * <ul>
     *   <li>{@code feeding_formula}: both {@code infant_feeding} and {@code general_health} required.</li>
     *   <li>{@code diaper_change} / {@code bathing}: {@code general_health} required.</li>
     *   <li>Missing consent → {@link SyncApplyResult.RejectedResult} ({@code consent_required}).</li>
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

        // Validate activityType
        String activityType = extractString(record, "activityType");
        if (activityType == null || !VALID_ACTIVITY_TYPES.contains(activityType)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error",
                            "activityType must be one of: " + String.join(", ", VALID_ACTIVITY_TYPES)));
        }

        // Per-row consent gate (INV-ASD-9)
        if ("feeding_formula".equals(activityType)) {
            // Dual gate: general_health + infant_feeding
            if (!consentChecker.isGranted(userId, "general_health")) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "consent_required", "general_health"));
            }
            if (!consentChecker.isGranted(userId, "infant_feeding")) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "consent_required", "infant_feeding"));
            }
        } else {
            // diaper_change / bathing: general_health only
            if (!consentChecker.isGranted(userId, "general_health")) {
                return new SyncApplyResult.RejectedResult(
                        new Rejected(COLLECTION, id, "consent_required", "general_health"));
            }
        }

        // Validate defaultQty
        Object dqRaw = record.get("defaultQty");
        int defaultQty = 0;
        if (dqRaw != null) {
            try {
                defaultQty = ((Number) dqRaw).intValue();
            } catch (ClassCastException | NullPointerException e) {
                defaultQty = 0;
            }
        }
        if (defaultQty < 0) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "defaultQty must be >= 0"));
        }

        long baseVersion = extractBaseVersion(record);
        ConsumptionMapping current = (ConsumptionMapping) existing;

        if (current == null) {
            return insertNew(userId, id, record, activityType, defaultQty);
        }

        if (current.getDeletedAt() != null) {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "tombstone_won", toRecord(current)));
        }

        long currentVersion = current.getVersion() != null ? current.getVersion() : 0L;
        if (baseVersion == currentVersion) {
            return updateExisting(current, userId, id, record, activityType, defaultQty);
        } else {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", toRecord(current)));
        }
    }

    private SyncApplyResult insertNew(UUID userId, UUID id, Map<String, Object> record,
                                       String activityType, int defaultQty) {
        ConsumptionMapping m = new ConsumptionMapping();
        m.setId(id);
        m.setUserId(userId);
        applyFields(m, record, activityType, defaultQty);
        try {
            m = repository.saveAndFlush(m);
            repository.initVersionToOne(m.getId());
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, m.getId(), 1L, m.getUpdatedAt()));
        } catch (Exception ex) {
            ConsumptionMapping reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", null));
        }
    }

    private SyncApplyResult updateExisting(ConsumptionMapping current, UUID userId, UUID id,
                                            Map<String, Object> record, String activityType,
                                            int defaultQty) {
        applyFields(current, record, activityType, defaultQty);
        try {
            current = repository.saveAndFlush(current);
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, current.getId(),
                            current.getVersion() != null ? current.getVersion() : 0L,
                            current.getUpdatedAt()));
        } catch (OptimisticLockingFailureException ex) {
            ConsumptionMapping reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            throw ex;
        }
    }

    // -------------------------------------------------------------------------
    // Apply delete — tombstone-wins, unconditional (NO consent check)
    // -------------------------------------------------------------------------

    /**
     * Tombstones the record unconditionally (tombstone-wins per sync spec §A.5).
     * Consent is NOT checked for deletes — tombstone propagation must never be blocked.
     * Never-seen id → inserts a tombstone skeleton (OQ-SYNC-10).
     */
    @Override
    @Transactional
    public Applied applyDelete(UUID userId, UUID id, Object existing) {
        ConsumptionMapping item = (ConsumptionMapping) existing;

        if (item == null) {
            // Never-seen id → tombstone skeleton (OQ-SYNC-10)
            ConsumptionMapping skeleton = new ConsumptionMapping();
            skeleton.setId(id);
            skeleton.setUserId(userId);
            skeleton.setActivityType("diaper_change"); // placeholder (satisfies NOT NULL)
            skeleton.setDefaultQty(0);
            skeleton.setEnabled(false);
            skeleton.setDeletedAt(Instant.now());
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

        // Apply tombstone (no crypto-shred needed — no *_cipher columns)
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
        List<ConsumptionMapping> rows = (cursorUpdatedAt == null)
                ? repository.findForPull(userId, since, pageable)
                : repository.findForPullAfterCursor(userId, since, cursorUpdatedAt, cursorId, pageable);
        return rows.stream()
                .map(m -> new PullRecord(m.getId(), m.getUpdatedAt(), m.getDeletedAt(), toRecord(m)))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Field mapping
    // -------------------------------------------------------------------------

    private void applyFields(ConsumptionMapping m, Map<String, Object> record,
                              String activityType, int defaultQty) {
        m.setActivityType(activityType);
        m.setDefaultQty(defaultQty);

        // supplyItemId: nullable soft reference (NO FK)
        String supplyItemIdStr = extractString(record, "supplyItemId");
        if (supplyItemIdStr != null) {
            try { m.setSupplyItemId(UUID.fromString(supplyItemIdStr)); }
            catch (Exception ignored) { m.setSupplyItemId(null); }
        } else {
            m.setSupplyItemId(null);
        }

        // enabled: nullable-tolerant; absent = true (default)
        Object enabledRaw = record.get("enabled");
        m.setEnabled(enabledRaw == null || Boolean.TRUE.equals(enabledRaw)
                || "true".equalsIgnoreCase(String.valueOf(enabledRaw)));

        // clientId
        String clientIdStr = extractString(record, "clientId");
        if (clientIdStr != null) {
            try { m.setClientId(UUID.fromString(clientIdStr)); } catch (Exception ignored) {}
        }
    }

    Map<String, Object> toRecord(ConsumptionMapping m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.getId());
        r.put("userId", m.getUserId());
        r.put("activityType", m.getActivityType());
        r.put("supplyItemId", m.getSupplyItemId());
        r.put("defaultQty", m.getDefaultQty());
        r.put("enabled", m.isEnabled());
        r.put("clientId", m.getClientId());
        r.put("version", m.getVersion() != null ? m.getVersion() : 0L);
        r.put("createdAt", m.getCreatedAt());
        r.put("updatedAt", m.getUpdatedAt());
        r.put("deletedAt", m.getDeletedAt());
        return r;
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
}
