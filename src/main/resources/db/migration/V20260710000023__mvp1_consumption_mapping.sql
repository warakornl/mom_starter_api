-- mvp1 — consumption_mapping: per-user activity→supply linking table (ASD health-side entity).
-- ASD carry-forward (data-model §3.14 / §5 item 3 / auto-stock-decrement-architecture §4 / §9 item 3).
--
-- ── DOMAIN BOUNDARY / CLASSIFICATION ─────────────────────────────────────────────────────────────
--
-- This is a HEALTH-SIDE entity (INV-ASD-9), NOT a supplies-collection record.
--
--   activity_type = 'feeding_formula': reveals the mother formula-feeds and which item she uses
--     → SD-10 sensitive health data; dual-gated infant_feeding + general_health (ม.20 + ม.26).
--   activity_type IN ('diaper_change', 'bathing'): care-activity→item link is health-adjacent
--     → general_health gate.
--
-- Storing this entity under cloud_storage-only (the supplies gate) is FORBIDDEN (INV-ASD-9):
--   that would expose "this mother formula-feeds + this specific item" to the supplies/cloud
--   tier without the SD-10 consent gate — a PDPA ม.26/27 violation per pdpa-assessment INV-ASD-9.
--
-- Gate enforcement is at the sync/push apply path (springboot-backend-dev):
--   feeding_formula rows are accepted only when infant_feeding AND general_health are granted;
--   diaper_change / bathing rows are accepted only when general_health is granted.
--   A withdrawn consent → the mapping stops syncing → stops triggering → no auto-decrement
--   (INV-ASD-1 / INV-ASD-3).
--
-- ── SUPPLY_ITEM_ID IS A SOFT REFERENCE — NO FK CASCADE ───────────────────────────────────────────
--
-- supply_item_id references a supply_items row but carries NO database FK constraint.
-- Rationale mirrors reminder.source_ref_id (data-model §3.14 / ASD §4):
--   • health-side and supply-side are separate domains; a hard FK would couple them at the DB layer.
--   • Deleting a supply_item does not cascade to consumption_mapping; the mobile client pushes
--     a tombstone for the mapping separately (two-tombstone pattern, same as Reminder/SupplyItem).
--   • The health→supply reference direction (mapping.supply_item_id → supply_items) is allowed;
--     the forbidden direction (supply_items referencing health events) does not exist.
--
-- ── MUTABLE RECORD → LWW ─────────────────────────────────────────────────────────────────────────
-- Same record-class as SupplyItem / MedicationPlan / ChecklistItem.
-- Reconciled by LWW on server updated_at + optimistic version.
-- New push-accepted collection `consumptionMappings` on the EXISTING sync rails (no new machinery).
--
-- ── CRYPTO-SHRED / RETENTION ─────────────────────────────────────────────────────────────────────
-- No *_cipher columns: no per-row crypto-shred sub-step needed (all fields are plaintext
-- integers / booleans / enum strings).
-- However, the ROWS THEMSELVES contain health correlate data and must be included in the
-- account-erasure tier-1 hard-purge (springboot-backend-dev: add 'consumption_mapping' to
-- TIER1_CHILD_DELETE_ORDER in AccountErasureService — no FK dependency on feeding_session /
-- reminder_occurrence, so it can be purged in any order alongside other health tables).
-- 180-day tombstone GC (TOMBSTONE_TTL) applies like every synced table.
-- Retention windows by activity_type:
--   feeding_formula → A5/A16 SD-10 window (180 d + per-account DEK shred-on-delete).
--   diaper_change, bathing → A17 general_health window (180 d, pdpa-assessment INV-ASD-9).
-- Both windows align at 180 d; no per-row differentiated GC is required.
--
-- ── id is CLIENT-GENERATED ───────────────────────────────────────────────────────────────────────
-- uuid v4, NOT a DB DEFAULT (sync spec §A.4 / data-model §2 — offline-safe, idempotent push).
--
-- ── FLAG-1 ───────────────────────────────────────────────────────────────────────────────────────
-- No user-asserted event time → no floating-civil bucket key.  Every timestamp in this table
-- belongs to the <sync> block (absolute UTC, LWW authority).  FLAG-1 satisfied trivially.
--
-- Reversibility: Forward-only (new table). Rollback = DROP TABLE consumption_mapping
--               (also DROP the indexes created below).

CREATE TABLE consumption_mapping (

    -- PK: CLIENT-GENERATED uuid v4.  No GENERATED / DEFAULT: server MUST NOT mint this id.
    id              uuid                        PRIMARY KEY,

    -- Owner.  NOT NULL; every mapping belongs to exactly one user.
    user_id         uuid                        NOT NULL REFERENCES users (id),

    -- activity_type: which health activity type drives the decrement.
    --   'feeding_formula'  → formula FeedingSession is the canonical trigger;
    --                         gate = infant_feeding (SD-10) + general_health (dual).
    --   'diaper_change'    → care-activity ReminderOccurrence done; gate = general_health.
    --   'bathing'          → care-activity ReminderOccurrence done; gate = general_health.
    --   CHECK not a native PG ENUM → reversible / additive (database-schema §0.2).
    activity_type   text                        NOT NULL
        CHECK (activity_type IN ('feeding_formula', 'diaper_change', 'bathing')),

    -- supply_item_id: the supply_items row that is decremented by this activity.
    --   SOFT REFERENCE — NO DATABASE FK, NO ON DELETE CASCADE.
    --   See "SUPPLY_ITEM_ID IS A SOFT REFERENCE" above.
    --   NULL is allowed (a mapping row may temporarily reference a deleted item;
    --   the mobile trigger skips enabled=true rows whose referenced item is deleted/gone).
    supply_item_id  uuid,

    -- default_qty: the per-use decrement amount in the linked item's sub-unit.
    --   Whole units for discrete (unlinked) items; sub-units for container-holds-N items.
    --   0 = a no-op mapping (linked but draws nothing — edge case, not rejected).
    --   CHECK >= 0: negative defaults are rejected (product 3c).
    --   NOT NULL: a mapping row must carry a default quantity.
    default_qty     integer                     NOT NULL
        CHECK (default_qty >= 0),

    -- enabled: whether this mapping is currently active for auto-decrement.
    --   TRUE  = the on-device trigger uses this row.
    --   FALSE = the mapping is preserved (e.g. temporarily disabled by the mother) but
    --           does NOT fire auto-decrement.
    enabled         boolean                     NOT NULL DEFAULT TRUE,

    -- <sync> block (data-model §1.2 / §2 / database-schema §0.3):
    --   created_at  : server-assigned on first INSERT; never changed after.
    --   updated_at  : server-assigned on EVERY apply; the single LWW merge clock.
    --   version     : server-assigned monotonic optimistic-concurrency token (@Version Long).
    --                 DEFAULT 0 is a safety net; the apply path stamps explicitly.
    --   deleted_at  : soft-delete tombstone.  NULL = live.  NOT NULL = tombstone (tombstone-wins).
    --                 180-day GC (TOMBSTONE_TTL) per database-schema §4.4.
    --                 No *_cipher columns → no crypto-shred on tombstone needed (rows purged by GC).
    --   client_id   : originating device uuid; LWW tie-break ONLY (sync spec §A.6).
    created_at      timestamp with time zone    NOT NULL DEFAULT now(),
    updated_at      timestamp with time zone    NOT NULL DEFAULT now(),
    version         bigint                      NOT NULL DEFAULT 0,
    deleted_at      timestamp with time zone,
    client_id       uuid

);

-- Sync-pull keyset index (database-schema §4.2 / offline-sync §B.4 / data-model §5).
-- Covers:
--   (a) sync/pull steady-state delta:
--         WHERE user_id = ? AND updated_at >= (watermark - safeWindow) ORDER BY updated_at, id
--   (b) GET /consumption-mappings cursor list (same keyset order).
--   (c) Cold-start drain keyset continuation.
-- INCLUDE (version) intentionally omitted for H2 compatibility (same convention as every other
-- sync-pull index in this codebase).
CREATE INDEX ix_consumption_mapping__sync_pull
    ON consumption_mapping (user_id, updated_at, id);

-- Completion→decrement lookup index (hot read path on server sync + mobile trigger).
--
-- Server-side use:  sync/pull for `consumptionMappings` fetches rows by user_id ordered by
--   updated_at (covered by __sync_pull above).  The consent-gate check on push (is this
--   activity_type allowed for this user?) is a simple WHERE user_id = ? AND activity_type = ?
--   predicate on a per-row basis; this index makes it sub-millisecond.
--
-- Mobile-side use (on-device SQLite, same schema shape):  when a completion event fires, the
--   trigger reads enabled ConsumptionMapping rows for the user + activity_type:
--       SELECT * FROM consumption_mapping
--       WHERE user_id = ? AND activity_type = ? AND enabled = TRUE AND deleted_at IS NULL
--   This index makes that lookup index-only on the mobile SQLite copy.
--
-- H2 compatibility note: a partial WHERE deleted_at IS NULL clause is intentionally omitted
-- here (same convention as ix_consumption_mapping__sync_pull omitting INCLUDE).  H2 in
-- PostgreSQL mode rejects partial index syntax; the full index provides the same query path
-- correctness (tombstoned rows appear in the index but are never matched by the
-- deleted_at IS NULL predicate at query time — no correctness impact).
-- PRODUCTION FOLLOW-UP: rebuild as partial (CONCURRENTLY) after initial deploy if index bloat
-- from tombstoned rows becomes a concern.
CREATE INDEX ix_consumption_mapping__trigger_lookup
    ON consumption_mapping (user_id, activity_type, enabled);
