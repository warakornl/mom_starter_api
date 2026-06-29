-- mvp1 — reminders: alarm-style reminder config (US-3/4, data-model §3.5).
-- Second entity wired with the offline-sync engine after supply_items (OQ-SYNC-18).
-- Calendar/reminder slice — api-contract §"Calendar · appointment · reminder-occurrence pins".
--
-- MOTHER-health collection.  Gated by general_health (per-collection) + cloud_storage (whole-batch).
-- Mutable record → LWW on server updated_at + optimistic version (same pattern as supply_items).
--
-- id is CLIENT-GENERATED (uuid v4) — NOT a DB DEFAULT (sync spec §A.4 / data-model §2).
-- start_at is FLOATING-CIVIL (timestamp WITHOUT TIME ZONE) — the FLAG-4 recurrence anchor (FLAG-1).
--   Never UTC-normalized; deterministic expansion uses this civil anchor byte-identically on
--   server + every device so projected and materialized instances share the same scheduledLocalCivil
--   string → the same uuidv5 occurrence id.
-- recurrence_rule is jsonb — the constrained FLAG-4 grammar
--   (freq: one_off|daily|every_n_days; interval?; timesOfDay[]?; until?).
--   Validated on sync/push (422) against the grammar schema (api-contract FLAG-4).
-- source_ref_id is a SOFT LINK (no FK) — client drives cancellation via two tombstones:
--   one for the referencing entity, one for this Reminder (data-model §3.9 / §5).
-- active: TRUE → the device schedules local alarms from this definition.
--
-- Reversibility: Forward-only (new table). Rollback = DROP TABLE reminders.

CREATE TABLE reminders (

    -- PK: CLIENT-GENERATED uuid v4.  No GENERATED / DEFAULT: server MUST NOT mint this id.
    id                  uuid                        PRIMARY KEY,

    -- Owner.  NOT NULL; every reminder belongs to exactly one user.
    user_id             uuid                        NOT NULL REFERENCES users(id),

    -- type: alarm category (data-model §3.5 / supplies-feature §3.3).
    -- supply_restock added for the supplies slice (data-model §3.9 carry-forward §5).
    type                text                        NOT NULL
                            CHECK (type IN (
                                'medication', 'kick_count', 'feeding',
                                'appointment', 'supply_restock', 'custom'
                            )),

    -- display_title: non-sensitive label shown on the lock screen (SD-11).  Required.
    display_title       text                        NOT NULL,

    -- source_ref_type / source_ref_id: optional soft link to the entity that spawned this
    -- reminder (a MedicationPlan, ChecklistItem, or SupplyItem).
    -- NO FK — client cancels linked reminders by pushing two tombstones over sync/push
    -- (one for the referencing entity, one for this Reminder).  data-model §3.9 / §5.
    source_ref_type     text
                            CHECK (source_ref_type IN (
                                'medication_plan', 'checklist_item', 'supply_item'
                            )),
    source_ref_id       uuid,

    -- recurrence_rule: the FLAG-4 constrained JSON grammar (data-model §3.5 / api-contract FLAG-4).
    -- Stored as jsonb; validated against the grammar on sync/push (422 on bad freq/interval/
    -- timesOfDay/until — see api-contract "Recurrence grammar & deterministic expansion §(a)").
    -- Application-layer enforcement is the primary guard; the DB column type is the physical home.
    recurrence_rule     jsonb                       NOT NULL,

    -- start_at: floating-civil anchor for deterministic recurrence expansion (FLAG-1 / FLAG-4).
    -- DB type: timestamp WITHOUT TIME ZONE — never UTC-normalized (civil wall-clock).
    -- Format (app-enforced): YYYY-MM-DDTHH:mm at minute precision.
    -- The same civil string is passed to uuidv5() expansion on both server and device
    -- so they produce the same scheduledLocalCivil values → the same occurrence ids.
    start_at            timestamp without time zone NOT NULL,

    -- active: TRUE → the device schedules local alarms from this Reminder definition.
    -- FALSE → definition is preserved (history / tombstone-tombstone-grace) but no new alarms.
    active              boolean                     NOT NULL DEFAULT TRUE,

    -- <sync> block (data-model §1.2 / §2 / database-schema §0.3).
    --   created_at  : server-assigned on first INSERT; never changed after.
    --   updated_at  : server-assigned on EVERY apply; the single LWW merge clock.
    --   version     : server-assigned monotonic optimistic-concurrency token (@Version Long).
    --                 DB DEFAULT 0 is a safety net; the apply path always stamps explicitly.
    --   deleted_at  : soft-delete tombstone.  NULL = live.  NOT NULL = tombstone (tombstone-wins,
    --                 offline-sync §A.5).  180-day GC (TOMBSTONE_TTL) per database-schema §4.4.
    --   client_id   : originating device uuid; LWW tie-break ONLY (sync spec §A.6).
    created_at          timestamp with time zone    NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone    NOT NULL DEFAULT now(),
    version             bigint                      NOT NULL DEFAULT 0,
    deleted_at          timestamp with time zone,
    client_id           uuid

);

-- Sync-pull keyset index (database-schema §4.2 / offline-sync §B.4 / data-model §5).
-- Covers three access patterns:
--   (a) sync/pull steady-state delta:
--         WHERE user_id = ? AND updated_at >= (watermark - safeWindow) ORDER BY updated_at, id
--   (b) GET /reminders cursor list (same keyset order).
--   (c) Cold-start drain keyset continuation:
--         AND (updated_at > cursorUpdatedAt OR (updated_at = cursorUpdatedAt AND id > cursorId))
-- INCLUDE (version) intentionally omitted for H2 compatibility (same as ix_supply_items__sync_pull).
CREATE INDEX ix_reminders__sync_pull ON reminders (user_id, updated_at, id);
