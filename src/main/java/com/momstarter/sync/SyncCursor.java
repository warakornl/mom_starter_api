package com.momstarter.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.error.ApiException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;


/**
 * Opaque cursor for cold-start / paginated {@code GET /sync/pull} drains
 * (api-contract §9 / OQ-SYNC-12).
 *
 * <p>Encodes as Base64-JSON. Fields:
 * <ul>
 *   <li>{@code w1} — snapshot-start instant (epoch ms): stamped ONCE at the first cursor-less
 *       request and returned unchanged on every batch. The client adopts it ONLY on the final
 *       batch ({@code nextCursor} absent). End-of-drain {@code now()} is FORBIDDEN (would lose
 *       mid-drain writes — see OQ-SYNC-12 / contract §9).</li>
 *   <li>{@code issued} — epoch ms when this cursor was issued; used for the 1h TTL check.</li>
 *   <li>{@code since} — original {@code since} param (epoch ms), held FIXED across the drain.</li>
 *   <li>{@code coll} — the collection currently being scanned (fixed order per registry).</li>
 *   <li>{@code atUs} — last row's {@code updated_at} (epoch µs); drives keyset continuation.
 *       Stored as microseconds (not ms) to avoid sub-millisecond overshoot in keyset queries.</li>
 *   <li>{@code id} — last row's {@code id}; keyset secondary sort key.</li>
 * </ul>
 *
 * <p>Cursor TTL: <strong>1 hour</strong>. Expired / tampered → {@code 400 invalid_cursor}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SyncCursor {

    private static final long TTL_SECONDS = 3600; // 1 hour

    // JSON-serialised fields (public for Jackson)
    public long w1;      // snapshot start (epoch ms)
    public long issued;  // issue time (epoch ms) — for TTL
    public long since;   // original since (epoch ms); 0 = cold start
    public String coll;  // current collection name
    /**
     * cursorUpdatedAt as epoch <strong>microseconds</strong> (not milliseconds).
     * Storing microseconds preserves the sub-millisecond precision that the DB
     * (H2 and PostgreSQL) uses, preventing keyset-overshoot: if stored as epoch-ms,
     * a row whose updatedAt is e.g. 355006µs would satisfy {@code updated_at > 355ms},
     * causing it to be re-delivered in the next batch despite being the cursor row itself.
     */
    public long atUs;    // cursorUpdatedAt (epoch µs — microseconds)
    public String id;    // cursorId (UUID string)

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a new cursor for the FIRST batch of a drain.
     *
     * <p>TODO: HMAC-sign cursor before prod (impact self-only, all queries JWT-scoped). §🟡-6
     *
     * @param w1     snapshot-start instant (stamped once at the first cursor-less request)
     * @param since  the original {@code since} parameter (0/EPOCH = cold start)
     * @param coll   collection where the scan will continue
     * @param lastAt {@code updated_at} of the last row in this batch
     * @param lastId {@code id} of the last row in this batch
     */
    public static SyncCursor create(Instant w1, Instant since,
                                     String coll, Instant lastAt, UUID lastId) {
        SyncCursor c = new SyncCursor();
        long now = Instant.now().toEpochMilli();
        c.w1 = w1.toEpochMilli();
        c.issued = now;
        c.since = since != null ? since.toEpochMilli() : 0L;
        c.coll = coll;
        c.atUs = ChronoUnit.MICROS.between(Instant.EPOCH, lastAt); // µs precision — no overshoot
        c.id = lastId.toString();
        return c;
    }

    /**
     * Creates a continuation cursor (advancing position within an ongoing drain).
     * Preserves the original {@code w1} and {@code issued} timestamps.
     */
    public static SyncCursor advance(SyncCursor prev, String newColl, Instant lastAt, UUID lastId) {
        SyncCursor c = new SyncCursor();
        c.w1 = prev.w1;
        c.issued = prev.issued;
        c.since = prev.since;
        c.coll = newColl;
        c.atUs = ChronoUnit.MICROS.between(Instant.EPOCH, lastAt);
        c.id = lastId.toString();
        return c;
    }

    /**
     * Creates a cursor pointing to the <em>start</em> of {@code coll} (no keyset position).
     * Used when the page budget is exhausted exactly at a collection boundary and there is a
     * next collection in PULL_ORDER (§5 fix — page boundary = collection boundary).
     *
     * <p>{@code atUs=0} and {@code id=null} signal "start of collection" to the pull loop;
     * {@link #cursorUpdatedAt()} returns {@code null} for atUs=0, which makes the query use
     * the full {@code since} range (no keyset predicate applied).
     *
     * <p>TODO: HMAC-sign cursor before prod (impact self-only, all queries JWT-scoped). §🟡-6
     */
    public static SyncCursor forCollectionStart(Instant w1, Instant since, String coll) {
        SyncCursor c = new SyncCursor();
        c.w1 = w1.toEpochMilli();
        c.issued = Instant.now().toEpochMilli();
        c.since = since != null ? since.toEpochMilli() : 0L;
        c.coll = coll;
        c.atUs = 0; // start-of-collection: cursorUpdatedAt() returns null for atUs=0
        c.id = null;
        return c;
    }

    // -------------------------------------------------------------------------
    // Encode / decode
    // -------------------------------------------------------------------------

    public String encode(ObjectMapper mapper) {
        try {
            byte[] json = mapper.writeValueAsBytes(this);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode cursor", e);
        }
    }

    /**
     * Decodes a cursor string and validates its TTL.
     *
     * @throws ApiException 400 {@code invalid_cursor} if the cursor is invalid or expired
     */
    public static SyncCursor decode(String encoded, ObjectMapper mapper) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(encoded);
            SyncCursor cursor = mapper.readValue(json, SyncCursor.class);
            if (cursor.coll == null || cursor.w1 == 0) {
                throw new ApiException(400, "invalid_cursor");
            }
            // TTL check: issued + 1h
            long expiresAt = cursor.issued + TTL_SECONDS * 1000;
            if (System.currentTimeMillis() > expiresAt) {
                throw new ApiException(400, "invalid_cursor");
            }
            return cursor;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(400, "invalid_cursor");
        }
    }

    // -------------------------------------------------------------------------
    // Accessors for the pull algorithm
    // -------------------------------------------------------------------------

    public Instant w1Instant() { return Instant.ofEpochMilli(w1); }

    public Instant sinceInstant() { return since > 0 ? Instant.ofEpochMilli(since) : null; }

    /** Returns the keyset-resume {@code updated_at} with microsecond precision, or {@code null}
     *  for a start-of-collection cursor ({@code atUs == 0}). */
    public Instant cursorUpdatedAt() {
        return atUs > 0 ? Instant.EPOCH.plus(atUs, ChronoUnit.MICROS) : null;
    }

    public UUID cursorId() {
        try { return id != null ? UUID.fromString(id) : null; } catch (Exception e) { return null; }
    }
}
