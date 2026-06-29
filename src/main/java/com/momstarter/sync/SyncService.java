package com.momstarter.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.error.ApiException;
import com.momstarter.pregnancy.ConsentChecker;
import com.momstarter.pregnancy.PregnancyProfile;
import com.momstarter.pregnancy.PregnancyProfileRepository;
import com.momstarter.sync.dto.Applied;
import com.momstarter.sync.dto.CollectionChanges;
import com.momstarter.sync.dto.CollectionPullChanges;
import com.momstarter.sync.dto.Conflict;
import com.momstarter.sync.dto.Rejected;
import com.momstarter.sync.dto.SyncPullResponse;
import com.momstarter.sync.dto.SyncPushRequest;
import com.momstarter.sync.dto.SyncPushResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core offline-sync engine — implements the apply (push) and query (pull) semantics
 * pinned in the api-contract "Offline-sync engine (PINNED)" §1–§10.
 *
 * <h3>Push algorithm (§A)</h3>
 * <ol>
 *   <li>Whole-call gates: auth (done in controller) → {@code cloud_storage} consent → batch cap.</li>
 *   <li>Per-collection: consent check → per-record upsert (version-arbitrated LWW S-A) → tombstone.</li>
 *   <li>Apply order per collection: {@code created → updated → deleted} (same-id → deleted wins).</li>
 * </ol>
 *
 * <h3>Pull algorithm (§B/§9)</h3>
 * <ol>
 *   <li>Whole-call gates: auth → {@code cloud_storage} consent → watermark-too-old check.</li>
 *   <li>Stamp snapshot-start W1 ONCE on the first cursor-less request; carry unchanged on every batch.</li>
 *   <li>Keyset scan per collection in fixed order {@code (updated_at ASC, id ASC)} with safe-window.</li>
 *   <li>Return {@code pregnancyProfile} (pull-replicated) at the end of each drain.</li>
 * </ol>
 */
@Service
public class SyncService {

    /** Total record cap per push (created + updated + deleted, all collections). OQ-SYNC-4. */
    static final int MAX_PUSH_RECORDS = 1000;

    /** Payload byte cap per push. OQ-SYNC-4. */
    static final long MAX_PUSH_BYTES = 5 * 1024 * 1024; // 5 MB

    /** Default pull limit (records across all collections). OQ-SYNC-11. */
    static final int DEFAULT_PULL_LIMIT = 1000;

    /** Maximum pull limit (records across all collections). OQ-SYNC-11. */
    static final int MAX_PULL_LIMIT = 5000;

    /** Default safe-window in seconds. OQ-SYNC-11. */
    static final int DEFAULT_SAFE_WINDOW_SECONDS = 5;

    /** Maximum safe-window in seconds (DoS cap). OQ-SYNC-11. */
    static final int MAX_SAFE_WINDOW_SECONDS = 60;

    /** Tombstone GC horizon — watermarks older than this force a full resync. OQ-SYNC-13. */
    static final int TOMBSTONE_TTL_DAYS = 180;

    // Pull-only collections not in the push registry
    private static final String PULL_ONLY_PREGNANCY_PROFILE = "pregnancyProfile";
    // Pull-only non-push-accepted collections (rejected if in push changes)
    private static final Set<String> PULL_REPLICATED_ONLY = Set.of(
            PULL_ONLY_PREGNANCY_PROFILE, "account", "consentRecords");

    private final SyncCollectionRegistry registry;
    private final ConsentChecker consentChecker;
    private final PregnancyProfileRepository profileRepository;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    public SyncService(SyncCollectionRegistry registry,
                       ConsentChecker consentChecker,
                       PregnancyProfileRepository profileRepository,
                       IdempotencyStore idempotencyStore,
                       ObjectMapper objectMapper) {
        this.registry = registry;
        this.consentChecker = consentChecker;
        this.profileRepository = profileRepository;
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // PUSH
    // =========================================================================

    /**
     * Applies a push batch (api-contract "Offline-sync engine" §1–§10).
     *
     * @param userId         authenticated user id (JWT sub)
     * @param request        the push body
     * @param idempotencyKey optional Idempotency-Key header value
     * @param payloadBytes   request Content-Length (or -1 if unknown)
     */
    @Transactional
    public SyncPushResponse push(UUID userId,
                                  SyncPushRequest request,
                                  String idempotencyKey,
                                  long payloadBytes) {
        // --- Validate required field: lastPulledAt ---
        if (request.lastPulledAt() == null) {
            throw new ApiException(400, "validation_error", "lastPulledAt is required");
        }

        // --- Idempotency replay (§10 / OQ-SYNC-15) ---
        if (idempotencyKey != null) {
            Optional<SyncPushResponse> cached = idempotencyStore.get(userId, idempotencyKey);
            if (cached.isPresent()) return cached.get();
        }

        // --- Gate 1: cloud_storage consent (whole-batch) ---
        if (!consentChecker.isGranted(userId, "cloud_storage")) {
            throw new ApiException(403, "consent_required", "cloud_storage");
        }

        // --- Gate 2: lastPulledAt watermark staleness check ---
        checkWatermarkNotExpired(request.lastPulledAt());

        // --- Gate 3: batch-cap check (1000 records / 5 MB) ---
        int totalRecords = request.changes().values().stream()
                .mapToInt(CollectionChanges::totalCount).sum();
        if (totalRecords > MAX_PUSH_RECORDS || (payloadBytes > 0 && payloadBytes > MAX_PUSH_BYTES)) {
            throw new ApiException(413, "batch_too_large");
        }

        List<Applied> applied = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();

        // --- Per-collection processing ---
        for (Map.Entry<String, CollectionChanges> entry : request.changes().entrySet()) {
            String collectionName = entry.getKey();
            CollectionChanges changes = entry.getValue();

            // Unknown / non-push-accepted collections → rejected[]
            if (PULL_REPLICATED_ONLY.contains(collectionName)) {
                rejected.add(new Rejected(collectionName, null, "unknown_collection", null));
                continue;
            }

            Optional<SyncCollection> collOpt = registry.find(collectionName);
            if (collOpt.isEmpty()) {
                rejected.add(new Rejected(collectionName, null, "unknown_collection", null));
                continue;
            }

            SyncCollection collection = collOpt.get();

            // Per-collection consent check
            String perCollConsent = collection.perCollectionConsentType();
            if (perCollConsent != null && !consentChecker.isGranted(userId, perCollConsent)) {
                rejected.add(new Rejected(collectionName, null, "consent_required", perCollConsent));
                continue;
            }

            // Batch-pre-load existing entities (avoid N+1)
            Set<UUID> allIds = collectIds(changes);
            Map<UUID, Object> existingByIds = collection.loadExisting(userId, allIds);

            // Apply: created → updated → deleted (same-id: deleted wins per apply-order)
            applyUpserts(userId, collection, changes.created(), existingByIds,
                         applied, conflicts, rejected);
            applyUpserts(userId, collection, changes.updated(), existingByIds,
                         applied, conflicts, rejected);
            applyDeletes(userId, collection, changes.deleted(), existingByIds, applied);
        }

        SyncPushResponse response = new SyncPushResponse(Instant.now(), applied, conflicts, rejected);

        // Store for idempotency replay
        if (idempotencyKey != null) {
            idempotencyStore.put(userId, idempotencyKey, response);
        }

        return response;
    }

    private void applyUpserts(UUID userId, SyncCollection collection,
                               List<Map<String, Object>> records,
                               Map<UUID, Object> existingByIds,
                               List<Applied> applied, List<Conflict> conflicts, List<Rejected> rejected) {
        for (Map<String, Object> record : records) {
            Object idRaw = record.get("id");
            UUID id = null;
            if (idRaw != null) {
                try { id = UUID.fromString(idRaw.toString()); } catch (Exception ignored) {}
            }
            Object existing = (id != null) ? existingByIds.get(id) : null;
            SyncApplyResult result = collection.applyUpsert(userId, record, existing);
            switch (result) {
                case SyncApplyResult.Success s -> applied.add(s.applied());
                case SyncApplyResult.ConflictResult c -> conflicts.add(c.conflict());
                case SyncApplyResult.RejectedResult r -> rejected.add(r.rejected());
            }
        }
    }

    private void applyDeletes(UUID userId, SyncCollection collection,
                               List<String> deletedIds,
                               Map<UUID, Object> existingByIds,
                               List<Applied> applied) {
        for (String idStr : deletedIds) {
            try {
                UUID id = UUID.fromString(idStr);
                Object existing = existingByIds.get(id);
                Applied result = collection.applyDelete(userId, id, existing);
                applied.add(result);
            } catch (IllegalArgumentException ignored) {
                // malformed UUID — skip silently
            }
        }
    }

    private Set<UUID> collectIds(CollectionChanges changes) {
        Set<UUID> ids = new HashSet<>();
        for (Map<String, Object> r : changes.created()) addId(ids, r.get("id"));
        for (Map<String, Object> r : changes.updated()) addId(ids, r.get("id"));
        for (String s : changes.deleted()) { try { ids.add(UUID.fromString(s)); } catch (Exception e) {} }
        return ids;
    }

    private void addId(Set<UUID> ids, Object idRaw) {
        if (idRaw == null) return;
        try { ids.add(UUID.fromString(idRaw.toString())); } catch (Exception ignored) {}
    }

    // =========================================================================
    // PULL
    // =========================================================================

    /**
     * Returns the change-set for the authenticated user (api-contract §B / §9 / OQ-SYNC-11–17).
     *
     * @param userId           authenticated user id
     * @param sinceStr         the {@code since} query param (ISO-8601 Instant or null/absent)
     * @param safeWindowSecs   requested safe-window override (clamped to [0,60])
     * @param cursorStr        continuation cursor from a prior batch (null for first batch)
     * @param limit            records per batch (default 1000, max 5000)
     */
    @Transactional(readOnly = true)
    public SyncPullResponse pull(UUID userId,
                                  String sinceStr,
                                  Integer safeWindowSecs,
                                  String cursorStr,
                                  Integer limit) {
        // --- Gate: cloud_storage consent ---
        if (!consentChecker.isGranted(userId, "cloud_storage")) {
            throw new ApiException(403, "consent_required", "cloud_storage");
        }

        // --- Parse since ---
        Instant since = parseSince(sinceStr);

        // --- Watermark-too-old check (OQ-SYNC-13) ---
        if (since != null && since.isBefore(Instant.now().minus(TOMBSTONE_TTL_DAYS, ChronoUnit.DAYS))) {
            throw new ApiException(409, "watermark_expired");
        }

        // --- Safe-window (clamped to [0, 60]) ---
        int sw = (safeWindowSecs != null) ? safeWindowSecs : DEFAULT_SAFE_WINDOW_SECONDS;
        sw = Math.min(Math.max(sw, 0), MAX_SAFE_WINDOW_SECONDS);
        Instant effectiveSince = (since != null)
                ? since.minus(sw, ChronoUnit.SECONDS)
                : Instant.EPOCH;

        // --- Page limit ---
        int pageLimit = (limit != null) ? Math.min(Math.max(limit, 1), MAX_PULL_LIMIT) : DEFAULT_PULL_LIMIT;

        // --- Decode cursor or start a new drain ---
        SyncCursor cursor = null;
        Instant w1;
        if (cursorStr != null) {
            cursor = SyncCursor.decode(cursorStr, objectMapper); // throws 400 if invalid
            w1 = cursor.w1Instant();
        } else {
            // Snapshot-start W1: stamped ONCE at the first cursor-less request (OQ-SYNC-12 / §9).
            // Truncated to milliseconds so it round-trips through cursor epoch-millis unchanged.
            w1 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        }

        // --- Drain collections in fixed order ---
        Map<String, CollectionPullChanges> changes = new LinkedHashMap<>();
        String nextCursor = null;
        int remaining = pageLimit;
        boolean drainComplete = true;

        // Determine where to resume (if cursor present)
        String resumeAtCollection = (cursor != null) ? cursor.coll : null;

        for (SyncCollection coll : registry.inPullOrder()) {
            String collName = coll.name();

            // Skip collections that are before the cursor's current collection
            if (resumeAtCollection != null && !collName.equals(resumeAtCollection)) {
                continue;
            }

            // Cursor position within this collection
            Instant cursorUpdatedAt = (cursor != null && collName.equals(cursor.coll))
                    ? cursor.cursorUpdatedAt() : null;
            UUID cursorId = (cursor != null && collName.equals(cursor.coll))
                    ? cursor.cursorId() : null;

            // Clear cursor after consuming it for this collection
            cursor = null;
            resumeAtCollection = null;

            // Fetch: limit+1 to detect hasMore within this collection
            Pageable pageable = Pageable.ofSize(remaining + 1);
            List<PullRecord> rows = coll.findForPull(userId, effectiveSince,
                    cursorUpdatedAt, cursorId, pageable);

            boolean collectionHasMore = rows.size() > remaining;
            if (collectionHasMore) {
                rows = rows.subList(0, remaining);
                drainComplete = false;
            }

            // Split into updated[] (live) and deleted[] (tombstones)
            List<Object> updatedRows = rows.stream()
                    .filter(r -> !r.isTombstone())
                    .map(PullRecord::data)
                    .collect(Collectors.toList());
            List<UUID> deletedIds = rows.stream()
                    .filter(PullRecord::isTombstone)
                    .map(PullRecord::id)
                    .collect(Collectors.toList());

            changes.put(collName, new CollectionPullChanges(List.of(), updatedRows, deletedIds));
            remaining -= rows.size();

            if (collectionHasMore) {
                // Build next cursor: W1 (unchanged snapshot start) + current position
                PullRecord last = rows.get(rows.size() - 1);
                SyncCursor nextCursorObj = (cursorStr == null)
                        ? SyncCursor.create(w1, since, collName, last.updatedAt(), last.id())
                        : SyncCursor.advance(
                            buildPrevCursorForAdvance(w1, since),
                            collName, last.updatedAt(), last.id());
                nextCursor = nextCursorObj.encode(objectMapper);
                break;
            }

            if (remaining <= 0) {
                drainComplete = false;
                break;
            }
        }

        // --- Append pregnancyProfile (pull-replicated, last in every batch when drain is ongoing) ---
        // Only include it if we have remaining capacity and this is the final batch of sync collections
        if (nextCursor == null) {
            addPregnancyProfile(userId, effectiveSince, changes);
        }

        return new SyncPullResponse(changes, w1, nextCursor);
    }

    private void addPregnancyProfile(UUID userId, Instant effectiveSince,
                                      Map<String, CollectionPullChanges> changes) {
        Optional<PregnancyProfile> profileOpt = profileRepository.findByUserId(userId);
        if (profileOpt.isEmpty()) return;

        PregnancyProfile profile = profileOpt.get();
        // Include only if updatedAt >= effectiveSince (safe-window already applied)
        if (profile.getUpdatedAt() == null || profile.getUpdatedAt().isBefore(effectiveSince)) {
            return;
        }

        Map<String, Object> profileRecord = buildProfileRecord(profile);

        if (profile.getDeletedAt() != null) {
            changes.put(PULL_ONLY_PREGNANCY_PROFILE,
                    new CollectionPullChanges(List.of(), List.of(), List.of(profile.getId())));
        } else {
            changes.put(PULL_ONLY_PREGNANCY_PROFILE,
                    new CollectionPullChanges(List.of(), List.of(profileRecord), List.of()));
        }
    }

    private Map<String, Object> buildProfileRecord(PregnancyProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("userId", p.getUserId());
        m.put("edd", p.getEdd() != null ? p.getEdd().toString() : null);
        m.put("eddBasis", p.getEddBasis());
        m.put("lifecycle", p.getLifecycle());
        m.put("birthDate", p.getBirthDate() != null ? p.getBirthDate().toString() : null);
        m.put("version", p.getVersion());
        m.put("createdAt", p.getCreatedAt());
        m.put("updatedAt", p.getUpdatedAt());
        m.put("deletedAt", p.getDeletedAt());
        return m;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Instant parseSince(String sinceStr) {
        if (sinceStr == null || sinceStr.isBlank() || "0".equals(sinceStr)) return null;
        try {
            return Instant.parse(sinceStr);
        } catch (Exception e) {
            return null; // treat unrecognised values as cold start
        }
    }

    private static void checkWatermarkNotExpired(String lastPulledAt) {
        if (lastPulledAt == null || "0".equals(lastPulledAt)) return;
        try {
            Instant ts = Instant.parse(lastPulledAt);
            if (ts.isBefore(Instant.now().minus(TOMBSTONE_TTL_DAYS, ChronoUnit.DAYS))) {
                throw new ApiException(409, "watermark_expired");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception ignored) {
            // un-parseable lastPulledAt → treat as zero watermark
        }
    }

    private SyncCursor buildPrevCursorForAdvance(Instant w1, Instant since) {
        SyncCursor prev = new SyncCursor();
        prev.w1 = w1.toEpochMilli();
        prev.issued = Instant.now().toEpochMilli();
        prev.since = since != null ? since.toEpochMilli() : 0L;
        return prev;
    }
}
