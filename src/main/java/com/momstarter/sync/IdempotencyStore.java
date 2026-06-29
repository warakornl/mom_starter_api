package com.momstarter.sync;

import com.momstarter.sync.dto.SyncPushResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency cache for {@code POST /sync/push} (api-contract §10 / OQ-SYNC-15).
 *
 * <p>Scope: <strong>per-subject</strong> (userId). Retention: <strong>24 hours</strong>.
 * A literal retry with the same {@code Idempotency-Key} within the window returns the
 * <strong>original stored response</strong> with no re-apply. This is the ONLY transport-level
 * un-bumped replay path — beyond 24h the key is forgotten and record-level idempotency takes over
 * (a re-sent mutable record resolves via version arbitration, OQ-SYNC-15).
 *
 * <p>TODO: Replace with a distributed cache (Redis) before horizontal scaling.
 */
@Component
public class IdempotencyStore {

    private static final long TTL_HOURS = 24;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(SyncPushResponse response, Instant expiry) {}

    /**
     * Returns the stored response if the key is still valid, otherwise empty.
     * Expired entries are lazily evicted on read.
     */
    public Optional<SyncPushResponse> get(UUID userId, String idempotencyKey) {
        String compositeKey = compositeKey(userId, idempotencyKey);
        Entry entry = store.get(compositeKey);
        if (entry == null) return Optional.empty();
        if (Instant.now().isAfter(entry.expiry())) {
            store.remove(compositeKey);
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    /**
     * Stores a response under the composite key {@code userId:idempotencyKey} with a 24h TTL.
     */
    public void put(UUID userId, String idempotencyKey, SyncPushResponse response) {
        store.put(compositeKey(userId, idempotencyKey),
                new Entry(response, Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS)));
    }

    private static String compositeKey(UUID userId, String key) {
        return userId.toString() + ':' + key;
    }
}
