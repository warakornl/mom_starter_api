package com.momstarter.pregnancy;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency cache for the four direct-REST pregnancy-profile verbs
 * ({@code PUT /pregnancy-profile}, {@code POST .../loss-event}, {@code POST .../reopen},
 * {@code POST .../birth-event}) — OR-BACKEND-1
 * (direct-rest-offline-resilience-architecture.md §4.3, direct-rest-offline-resilience-functional.md §8).
 *
 * <p>A SEPARATE, parallel store to {@code com.momstarter.sync.IdempotencyStore} — deliberately
 * NOT a generalisation of that {@code SyncPushResponse}-typed cache, per the architecture's
 * explicit instruction (lower blast radius; the shipped {@code SyncService} call-site is left
 * untouched). This store caches an HTTP status + response body pair per verb call instead.
 *
 * <p>Scope: <strong>per-subject</strong> (userId) — composite key {@code userId:idempotencyKey}
 * so one user's key can never replay another user's stored result. Retention:
 * <strong>24 hours</strong>, matching {@code IdempotencyStore} verbatim (OQ-SYNC-15 shape).
 * Expired entries are lazily evicted on read.
 *
 * <p><strong>Only successful (2xx) outcomes are ever stored</strong> (see call-sites in
 * {@link PregnancyProfileController}) — a transient {@code 409} conflict is never cached
 * because it reflects the state AT THAT MOMENT, not the entry's settled intent; a retry with a
 * corrected {@code If-Match} must be free to re-evaluate against the live version, not replay a
 * stale conflict (functional-spec §8, OR-INV-4).
 *
 * <p>TODO: Replace with a distributed cache (Redis) before horizontal scaling — same note as
 * the shipped {@code IdempotencyStore}.
 */
@Component
public class ProfileVerbIdempotencyStore {

    private static final long TTL_HOURS = 24;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * A stored verb outcome: the HTTP status actually returned, and the response body
     * (a {@code PregnancyProfileResponse} for every current verb, but kept as {@code Object}
     * so this store stays agnostic of the specific DTO type).
     */
    public record StoredResult(int status, Object body) {}

    private record Entry(int status, Object body, Instant expiry) {}

    /**
     * Returns the stored result if the key is still valid for this user, otherwise empty.
     * Expired entries are lazily evicted on read.
     */
    public Optional<StoredResult> get(UUID userId, String idempotencyKey) {
        String compositeKey = compositeKey(userId, idempotencyKey);
        Entry entry = store.get(compositeKey);
        if (entry == null) return Optional.empty();
        if (Instant.now().isAfter(entry.expiry())) {
            store.remove(compositeKey);
            return Optional.empty();
        }
        return Optional.of(new StoredResult(entry.status(), entry.body()));
    }

    /**
     * Stores a verb outcome under the composite key {@code userId:idempotencyKey} with a
     * 24h TTL from now.
     */
    public void put(UUID userId, String idempotencyKey, int status, Object body) {
        putWithExpiry(userId, idempotencyKey, status, body,
                Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS));
    }

    /**
     * Stores a verb outcome with an explicit expiry instant. Package-visible test seam so
     * TTL-elapse behaviour can be verified without waiting 24 real hours.
     */
    void putWithExpiry(UUID userId, String idempotencyKey, int status, Object body, Instant expiry) {
        store.put(compositeKey(userId, idempotencyKey), new Entry(status, body, expiry));
    }

    private static String compositeKey(UUID userId, String key) {
        return userId.toString() + ':' + key;
    }
}
