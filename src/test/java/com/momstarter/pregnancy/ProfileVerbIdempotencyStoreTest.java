package com.momstarter.pregnancy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProfileVerbIdempotencyStore} — the second, parallel idempotency cache
 * for the four direct-REST profile verbs (birth-event, loss-event, reopen, PUT profile),
 * mirroring the shipped {@code com.momstarter.sync.IdempotencyStore} pattern verbatim
 * (per-subject key, 24h TTL, in-memory {@code ConcurrentHashMap}, lazy-evict) WITHOUT touching
 * or generalising that store (architecture §4.3 / OR-BACKEND-1 — do NOT make
 * {@code SyncPushResponse}-typed store double-duty).
 *
 * <p>RED: fails until {@link ProfileVerbIdempotencyStore} exists with {@code get}/{@code put}.
 */
class ProfileVerbIdempotencyStoreTest {

    private final ProfileVerbIdempotencyStore store = new ProfileVerbIdempotencyStore();

    @Test
    void get_noEntryStored_returnsEmpty() {
        UUID userId = UUID.randomUUID();
        assertThat(store.get(userId, "some-key")).isEmpty();
    }

    @Test
    void put_thenGet_returnsStoredStatusAndBody() {
        UUID userId = UUID.randomUUID();
        String key = "idem-key-1";
        Object body = "fake-profile-response";

        store.put(userId, key, 200, body);

        var found = store.get(userId, key);
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(200);
        assertThat(found.get().body()).isEqualTo(body);
    }

    @Test
    void differentUsers_sameKey_doNotCollide() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        String key = "shared-literal-key";

        store.put(userA, key, 200, "profile-for-A");

        // User B must NOT see user A's stored result for the "same" key string
        // (composite key is userId:key — OR-BACKEND-1 "one user's key can't replay another's").
        assertThat(store.get(userB, key)).isEmpty();
    }

    @Test
    void differentKeys_sameUser_doNotCollide() {
        UUID userId = UUID.randomUUID();
        store.put(userId, "key-A", 200, "response-A");

        assertThat(store.get(userId, "key-B")).isEmpty();
        assertThat(store.get(userId, "key-A")).isPresent();
    }

    @Test
    void expiredEntry_isLazilyEvicted_returnsEmpty() {
        UUID userId = UUID.randomUUID();
        String key = "expiring-key";

        // Store with an already-past expiry to simulate TTL elapse without waiting 24h.
        store.putWithExpiry(userId, key, 200, "stale-response",
                java.time.Instant.now().minusSeconds(1));

        assertThat(store.get(userId, key)).isEmpty();
    }
}
