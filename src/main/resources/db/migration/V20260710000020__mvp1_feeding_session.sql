-- mvp1 — feeding_session: breast/pump/formula feed log (SD-4 / SD-10 health data).
-- ASD carry-forward (data-model §3.14 / auto-stock-decrement-architecture §2 / §9 item 1):
--   `formula` kind is added from the start (A5 ≡ A16 resolution — no separate BabyFeedingLog);
--   `amount_sub_units` carries the per-formula-feed scoop/serving count for the
--   on-device auto-decrement trigger.
--
-- CAPTURE TYPE: IMMUTABLE EVENT (create-on-push, union-merge, soft-delete via tombstone only).
--   No UPDATE of any field after push; any correction = soft-delete + re-log.
--   Record class mirrors kick_count_session / self_log / medication_log.
--
-- CONSENT GATE (dual-gate — unchanged from the existing feeding contract, ruling 6):
--   Both `infant_feeding` AND `general_health` MUST be granted for a feeding_session row
--   to be accepted in sync/push (per-collection gate) and to be included in the PDF (A11).
--   A kind=formula row is rejected from sync/push if either consent is absent → no log
--   → no auto-decrement trigger fires (INV-ASD-1).
--   `formula` + `amount_sub_units` inherit the existing dual-gate with ZERO new consent wiring
--   (data-model §3.14 / auto-stock-decrement-architecture §2 "Consent behaviour").
--
-- DATA CLASSIFICATION: SD-10 (`infant_feeding`, ม.20 parental + ม.26 sensitive health).
--   note_cipher: Option A bytea (same seam as kick_count_session.note_cipher / self_log values).
--   amount_sub_units: plaintext integer (a decrement amount — not health meaning per se;
--   the value is never parsed or aggregated server-side, G4 / INV-ASD-4).
--   Stored HEALTH-side; NEVER copied to the supplies collection (INV-ASD-4).
--
-- INV-ASD-4 (binding): this table carries NO timestamp-of-decrement, NO per-feed FK on
--   supply_item, NO fed_at column. The server ZERO-LINKAGE guarantee is enforced by
--   construction: there is no column on this table that ties to the supplies side,
--   and no column on supply_items that ties to this table.
--   supply_item.uses_remaining_in_open_container is ABSENT from the server schema (INV-ASD-8).
--
-- CRYPTO-SHRED OBLIGATION (PDPA ม.33 / database-schema §4.4(A)):
--   On soft-delete (deleted_at stamped): note_cipher → NULL in the same UPDATE.
--   On DELETE /account (A13): per-account DEK destruction + hard-purge of this table
--   (springboot-backend-dev adds feeding_session to TIER1_CHILD_DELETE_ORDER in
--   AccountErasureService, after medication_log / self_log — no FK dependency here).
--
-- RETENTION (RoPA A5 / A16 — same entity after A5≡A16 resolution):
--   180-day tombstone GC (TOMBSTONE_TTL) + per-account DEK crypto-shred on DELETE /account.
--   Window confirmed with security-compliance (same as self_log / kick_count_session).
--
-- FLAG-1 (two-clock rule): started_at is the FLOATING-CIVIL calendar bucket key
--   (timestamp WITHOUT TIME ZONE, never UTC-normalized). All <sync> timestamps are
--   absolute UTC (timestamp WITH TIME ZONE, LWW/merge authority). The two never interfere.
--
-- id is CLIENT-GENERATED (uuid v4) — NOT a DB DEFAULT (sync spec §A.4 / data-model §2).
--   Re-push of the same id at the same version is an idempotent no-op (sync §A.8).
--
-- Reversibility: Forward-only (new table). Rollback = DROP TABLE feeding_session.

CREATE TABLE feeding_session (

    -- PK: CLIENT-GENERATED uuid v4.  No GENERATED / DEFAULT.
    id                  uuid                        PRIMARY KEY,

    -- Owner.  NOT NULL; every session belongs to exactly one user.
    user_id             uuid                        NOT NULL REFERENCES users (id),

    -- kind: the feed modality.
    --   'breastfeed' — breast milk, direct nursing.
    --   'pump'       — expressed breast milk.
    --   'formula'    — formula feed (§3.14 / ASD §2).  The canonical formula-feed log;
    --                  a feeding reminder-done NEVER decrements (ASD §1.1 / INV-ASD-1).
    --   CHECK not a native PG ENUM → reversible/additive (database-schema §0.2).
    kind                text                        NOT NULL
        CHECK (kind IN ('breastfeed', 'pump', 'formula')),

    -- side: which breast was used (left/right/both), nullable (not applicable for pump/formula).
    side                text
        CHECK (side IN ('left', 'right', 'both')),

    -- started_at: FLOATING-CIVIL calendar BUCKET KEY (FLAG-1, data-model §3.2 / §5).
    --   DB type: timestamp WITHOUT TIME ZONE — never UTC-normalized.
    --   The DATE part groups sessions by calendar day in history and the PDF.
    started_at          timestamp without time zone NOT NULL,

    -- duration_seconds: elapsed feed time in whole seconds.  Optional (nullable).
    --   CLIENT-COMPUTED and stored VERBATIM — server NEVER recomputes (DRIFT-1).
    duration_seconds    integer
        CHECK (duration_seconds >= 0),

    -- volume_ml: expressed/formula volume in millilitres.  Optional (nullable).
    --   Exact numeric — never float (no rounding drift, G4).
    volume_ml           numeric
        CHECK (volume_ml >= 0),

    -- amount_sub_units: integer servings/scoops given THIS feed in the linked item's sub-unit.
    --   Meaningful ONLY when kind = 'formula'; MUST be NULL for breastfeed/pump (see constraint
    --   ck_feeding_session__amount_sub_units_formula below).
    --   The decrement amount for the on-device auto-decrement trigger (ASD §2 / §3.14).
    --   Constraint: non-negative integer; 0 = a logged feed with zero scoops (no-op use, allowed).
    --   HEALTH-SIDE (SD-10): NEVER copied to the supplies side (INV-ASD-4).
    --   Server NEVER parses, aggregates, or interprets this value (G4).
    amount_sub_units    integer
        CHECK (amount_sub_units >= 0),

    -- note_cipher: optional client-encrypted free-text note (bytea, Option A).
    --   NULL when the user did not enter a note.  Never parsed server-side.
    --   Crypto-shred: → NULL on soft-delete (same row UPDATE that stamps deleted_at).
    note_cipher         bytea,

    -- <sync> block (data-model §1.2 / §2 / database-schema §0.3):
    --   created_at  : server-assigned on first INSERT; never changed after.
    --   updated_at  : server-assigned on EVERY apply; for an immutable event, bumped only
    --                 on tombstone (a re-push at the same version is a no-op — sync §A.8).
    --   version     : server-assigned monotonic optimistic-concurrency token (@Version Long).
    --                 DEFAULT 0 is a safety net; the apply path stamps explicitly.
    --   deleted_at  : soft-delete tombstone.  NULL = live.  NOT NULL = tombstone (tombstone-wins).
    --                 180-day GC (TOMBSTONE_TTL) per database-schema §4.4.
    --                 On tombstone: crypto-shred note_cipher → NULL in the same UPDATE.
    --   client_id   : originating device uuid; LWW tie-break ONLY (sync spec §A.6).
    created_at          timestamp with time zone    NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone    NOT NULL DEFAULT now(),
    version             bigint                      NOT NULL DEFAULT 0,
    deleted_at          timestamp with time zone,
    client_id           uuid,

    -- Cross-field integrity guard: amount_sub_units is meaningful only for formula feeds.
    -- Breastfeed / pump sessions MUST NOT carry a scoop count — prevents data corruption
    -- (a non-formula session erroneously decrementing formula stock via a non-null sub-unit
    -- would be a silent data integrity failure).
    CONSTRAINT ck_feeding_session__amount_sub_units_formula
        CHECK (kind = 'formula' OR amount_sub_units IS NULL)

);

-- Sync-pull keyset index (database-schema §4.2 / offline-sync §B.4 / data-model §5).
-- Covers:
--   (a) sync/pull steady-state delta:
--         WHERE user_id = ? AND updated_at >= (watermark - safeWindow) ORDER BY updated_at, id
--   (b) GET /feeding-sessions cursor list (same keyset order).
--   (c) Cold-start drain keyset continuation (row-value cursor).
-- INCLUDE (version) intentionally omitted for H2 compatibility (same convention as every other
-- sync-pull index — see V20260629000006 / V20260630000010 for rationale).
CREATE INDEX ix_feeding_session__sync_pull ON feeding_session (user_id, updated_at, id);

-- History / calendar index (floating-civil bucket key, FLAG-1).
-- Covers: GET /feeding-sessions?from=&to= range read keyed on started_at.
-- started_at is the civil-date bucket key; a backdated feed buckets on its civil date and
-- never shifts under time-zone travel (FLAG-1 guarantee).  Residual filter on deleted_at
-- is intentional — feed cardinality per user is low (a few sessions per day).
CREATE INDEX ix_feeding_session__history ON feeding_session (user_id, started_at, id);
