package com.momstarter.sync;

import com.momstarter.expense.Expense;
import com.momstarter.expense.ExpenseRepository;
import com.momstarter.sync.dto.Applied;
import com.momstarter.sync.dto.Conflict;
import com.momstarter.sync.dto.Rejected;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link SyncCollection} implementation for the {@code expenses} collection.
 *
 * <p>Second NON-health sync collection (after {@code supplyItems}), following the identical
 * pattern: mutable LWW record reconciled by server {@code updated_at} + optimistic
 * {@code version}. Gated by {@code cloud_storage} (whole-batch) ONLY —
 * {@link #perCollectionConsentType()} returns {@code null}.
 *
 * <h3>Consent gate</h3>
 * <p>NON-health (expenses-ui.md §0): {@code healthcare} category is a spending label,
 * NOT a health record. Gated by {@code cloud_storage} only — same as {@code supplyItems}.
 *
 * <h3>Validation</h3>
 * <ul>
 *   <li>{@code id} must be non-null (UUID).</li>
 *   <li>{@code amount} must be present and ≥ 0 (in satang).</li>
 *   <li>{@code category} must be one of {@code baby-supplies|healthcare|baby-gear|mother|other}.</li>
 *   <li>{@code incurredOn} must be present and parseable as ISO-8601 date (yyyy-MM-dd).</li>
 * </ul>
 *
 * <h3>Tombstone skeleton (OQ-SYNC-10)</h3>
 * <p>Deleting a never-seen id inserts a skeleton row ({@code amount=0}, {@code category="other"},
 * {@code incurredOn=EPOCH}, {@code deleted_at=now()}) so the tombstone converges to other devices.
 */
@Component
class ExpenseSyncCollection implements SyncCollection {

    private static final String COLLECTION = "expenses";

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "baby-supplies", "healthcare", "baby-gear", "mother", "other");

    private static final LocalDate EPOCH_DATE = LocalDate.of(1970, 1, 1);

    private final ExpenseRepository repository;

    ExpenseSyncCollection(ExpenseRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return COLLECTION;
    }

    /**
     * NON-health collection — gated by {@code cloud_storage} only (whole-batch gate).
     * No per-collection consent gate.
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
                .collect(Collectors.toMap(Expense::getId, e -> (Object) e));
    }

    // -------------------------------------------------------------------------
    // Apply upsert (optimistic CAS / version-arbitrated LWW S-A)
    // -------------------------------------------------------------------------

    /**
     * Applies one upsert using the version-arbitrated LWW (S-A) conflict rule:
     * <ul>
     *   <li>No server row → INSERT ({@code version:=0} via Hibernate, then bumped to 1).</li>
     *   <li>Server row tombstoned → {@code tombstone_won} conflict.</li>
     *   <li>{@code baseVersion == currentVersion} → UPDATE + bump version → {@code applied[]}.</li>
     *   <li>{@code baseVersion < currentVersion} → {@code server_won} conflict.</li>
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

        // Validate amount (required, ≥ 0)
        Object amountRaw = record.get("amount");
        if (amountRaw == null) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "amount is required"));
        }
        int amount;
        try {
            amount = ((Number) amountRaw).intValue();
        } catch (Exception e) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "amount must be a number"));
        }
        if (amount < 0) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "amount must be >= 0"));
        }

        // Validate category (required, one of 5 enum values)
        String category = extractString(record, "category");
        if (category == null || !VALID_CATEGORIES.contains(category)) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error",
                            "category must be one of: " + String.join(", ", VALID_CATEGORIES)));
        }

        // Validate incurredOn (required, ISO-8601 date)
        String incurredOnStr = extractString(record, "incurredOn");
        if (incurredOnStr == null || incurredOnStr.isBlank()) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error", "incurredOn is required"));
        }
        LocalDate incurredOn;
        try {
            incurredOn = LocalDate.parse(incurredOnStr);
        } catch (DateTimeParseException e) {
            return new SyncApplyResult.RejectedResult(
                    new Rejected(COLLECTION, id, "validation_error",
                            "incurredOn must be an ISO-8601 date (yyyy-MM-dd)"));
        }

        long baseVersion = extractBaseVersion(record);
        Expense current = (Expense) existing;

        if (current == null) {
            return insertNew(userId, id, record, amount, category, incurredOn);
        }

        // Tombstone-wins (unconditional)
        if (current.getDeletedAt() != null) {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "tombstone_won", toRecord(current)));
        }

        // Version-arbitrated LWW (S-A)
        long currentVersion = current.getVersion() != null ? current.getVersion() : 0L;
        if (baseVersion == currentVersion) {
            return updateExisting(current, userId, id, record, amount, category, incurredOn);
        } else {
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", toRecord(current)));
        }
    }

    private SyncApplyResult insertNew(UUID userId, UUID id, Map<String, Object> record,
                                       int amount, String category, LocalDate incurredOn) {
        Expense item = new Expense();
        item.setId(id);
        item.setUserId(userId);
        applyFields(item, record, amount, category, incurredOn);
        try {
            item = repository.saveAndFlush(item); // Hibernate @Version seeds to 0 on INSERT
            repository.initVersionToOne(item.getId()); // bump to 1 (contract §5 pin)
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, item.getId(), 1L, item.getUpdatedAt()));
        } catch (Exception ex) {
            Expense reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
                    .stream().findFirst().orElse(null);
            if (reloaded != null) {
                return new SyncApplyResult.ConflictResult(
                        new Conflict(COLLECTION, id, "server_won", toRecord(reloaded)));
            }
            return new SyncApplyResult.ConflictResult(
                    new Conflict(COLLECTION, id, "server_won", null));
        }
    }

    private SyncApplyResult updateExisting(Expense current, UUID userId, UUID id,
                                            Map<String, Object> record, int amount,
                                            String category, LocalDate incurredOn) {
        applyFields(current, record, amount, category, incurredOn);
        try {
            current = repository.saveAndFlush(current);
            return new SyncApplyResult.Success(
                    new Applied(COLLECTION, current.getId(),
                            current.getVersion() != null ? current.getVersion() : 0L,
                            current.getUpdatedAt()));
        } catch (OptimisticLockingFailureException ex) {
            Expense reloaded = repository.findByUserIdAndIdIn(userId, Set.of(id))
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
     */
    @Override
    @Transactional
    public Applied applyDelete(UUID userId, UUID id, Object existing) {
        Expense item = (Expense) existing;

        if (item == null) {
            // Never-seen id → insert skeleton tombstone (OQ-SYNC-10)
            Expense skeleton = new Expense();
            skeleton.setId(id);
            skeleton.setUserId(userId);
            skeleton.setAmount(0);           // placeholder
            skeleton.setCategory("other");   // placeholder
            skeleton.setIncurredOn(EPOCH_DATE); // placeholder
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
        List<Expense> rows = (cursorUpdatedAt == null)
                ? repository.findForPull(userId, since, pageable)
                : repository.findForPullAfterCursor(userId, since, cursorUpdatedAt, cursorId, pageable);

        return rows.stream()
                .map(e -> new PullRecord(e.getId(), e.getUpdatedAt(), e.getDeletedAt(), toRecord(e)))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Field mapping helpers
    // -------------------------------------------------------------------------

    private void applyFields(Expense item, Map<String, Object> record,
                              int amount, String category, LocalDate incurredOn) {
        item.setAmount(amount);
        item.setCategory(category);
        item.setIncurredOn(incurredOn);
        item.setNote(extractString(record, "note"));

        // clientId — originating device UUID
        String clientIdStr = extractString(record, "clientId");
        if (clientIdStr != null) {
            try { item.setClientId(UUID.fromString(clientIdStr)); } catch (Exception ignored) {}
        }
    }

    /**
     * Converts an {@link Expense} to a serialisable {@link Map} for
     * {@code serverRecord} (conflicts) and pull {@code updated[]} entries.
     */
    Map<String, Object> toRecord(Expense item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("userId", item.getUserId());
        m.put("amount", item.getAmount());
        m.put("category", item.getCategory());
        m.put("incurredOn", item.getIncurredOn() != null ? item.getIncurredOn().toString() : null);
        m.put("note", item.getNote());
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
        try { return ((Number) val).longValue(); } catch (Exception e) { return 0L; }
    }
}
