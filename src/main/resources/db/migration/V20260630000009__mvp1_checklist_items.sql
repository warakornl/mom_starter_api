-- mvp1 — checklist_items: mark-done calendar entries (data-model §3.4, US-12).
-- Capture type (c): covers ANC visits, labs, screenings, vaccines, hospital-bag tasks,
-- postpartum checks, AND user appointments (category=appointment — single entity keeps
-- capture type (c) DRY; see technical-decisions.md §7).
-- MOTHER-health collection.  Gated by general_health (per-collection) + cloud_storage (whole-batch).
-- Mutable record → LWW on server updated_at + optimistic version.
--
-- APPOINTMENT FIELD MODEL (OQ-CAL-1 RESOLVED — R-A, PINNED in api-contract FLAG-7):
--   Location/doctor/clinic/phone have NO structured column in MVP.
--   They are folded into the free-text `note` (and/or title), UNSTRUCTURED, NEVER parsed (G4).
--   R-B (structured location_text/provider_text, possibly client-encrypted) is DEFERRED.
--   No schema change this phase.
--
-- APPOINTMENT REQUIREDNESS (OQ-CAL-2 RESOLVED — client business rule):
--   appointment + anc_visit items MUST be dated (scheduled_at ≠ null) — CLIENT-ENFORCED.
--   The server stores scheduled_at verbatim; no DB-level per-category NOT NULL constraint.
--
-- id is CLIENT-GENERATED (uuid v4) — NOT a DB DEFAULT (sync spec §A.4 / data-model §2).
-- scheduled_at is FLOATING-CIVIL (timestamp WITHOUT TIME ZONE, NULLABLE for undated tasks — FLAG-1).
--   NULL is allowed for checklist_task/hospital-bag items that carry no target date.
--   The date part is the calendar bucket key; never UTC-normalized (same rule as reminder.start_at).
-- done_at is ABSOLUTE-UTC (timestamptz) — the server-clock action instant; NOT a bucket key.
-- note is NEVER PARSED (G4) — how lab/screening "results" are captured per SD-7.
-- source_suggestion_state_id is a SOFT LINK (no FK).
--
-- Reversibility: Forward-only (new table). Rollback = DROP TABLE checklist_items.

CREATE TABLE checklist_items (

    -- PK: CLIENT-GENERATED uuid v4.  No GENERATED / DEFAULT.
    id                          uuid                        PRIMARY KEY,

    -- Owner.  NOT NULL.
    user_id                     uuid                        NOT NULL REFERENCES users(id),

    -- category: the capture-type-c kind of this item.
    -- appointment     : user appointment (US-12); always dated (client rule).
    -- anc_visit       : ANC visit; always dated (client rule).
    -- lab_panel       : ordered lab panel.
    -- screening       : screening (e.g. OGTT, Group B strep, anomaly scan).
    -- vaccine         : vaccine appointment.
    -- checklist_task  : hospital-bag / generic undated or dated task.
    -- postpartum_check: postpartum follow-up.
    category                    text                        NOT NULL
                                    CHECK (category IN (
                                        'appointment', 'anc_visit', 'lab_panel',
                                        'screening', 'vaccine',
                                        'checklist_task', 'postpartum_check'
                                    )),

    -- title: free-text label.  Language-agnostic, stored verbatim, never parsed.
    title                       text                        NOT NULL,

    -- scheduled_at: floating-civil calendar bucket key (FLAG-1).
    -- NULL for undated tasks (hospital-bag items etc.).
    -- appointment/anc_visit are always dated — client rule, NOT a DB CHECK (server stores verbatim).
    -- DB type: timestamp WITHOUT TIME ZONE — never UTC-normalized.
    -- All-day appointment → stored as YYYY-MM-DDT00:00 (client derives the all-day flag from picker).
    scheduled_at                timestamp without time zone,

    -- done: completion flag.  FALSE = open, TRUE = done.  Default FALSE.
    done                        boolean                     NOT NULL DEFAULT FALSE,

    -- done_at: absolute-UTC server-clock instant when marked done.  NULL while done = FALSE.
    -- This is a system/action instant (not a bucket key) — stored as timestamptz (FLAG-1).
    done_at                     timestamp with time zone,

    -- note: free-text, NEVER parsed (G4).
    -- SD-7: this is how lab/screening "results" and observation notes are captured.
    -- OQ-CAL-1 R-A: location/doctor/clinic details are folded into this field.
    -- Language-agnostic, stored verbatim, echoed back only.
    note                        text,

    -- source: how this item was created.
    -- user_created   : directly by the user or via the appointment CRUD flow.
    -- from_suggestion: spawned from a UserSuggestionState (US-8/9 Start flow).
    source                      text                        NOT NULL DEFAULT 'user_created'
                                    CHECK (source IN ('user_created', 'from_suggestion')),

    -- source_suggestion_state_id: soft link to the UserSuggestionState that spawned this item.
    -- NULL for user_created items.  No FK (suggestion states may be tombstoned).
    source_suggestion_state_id  uuid,

    -- <sync> block (data-model §1.2 / §2).
    --   created_at  : server-assigned on first INSERT; never changed.
    --   updated_at  : server-assigned on EVERY apply; the single LWW merge clock.
    --   version     : server-assigned monotonic optimistic-concurrency token (@Version Long).
    --   deleted_at  : soft-delete tombstone.  NULL = live.  NOT NULL = tombstone (tombstone-wins).
    --   client_id   : originating device uuid; LWW tie-break ONLY.
    created_at                  timestamp with time zone    NOT NULL DEFAULT now(),
    updated_at                  timestamp with time zone    NOT NULL DEFAULT now(),
    version                     bigint                      NOT NULL DEFAULT 0,
    deleted_at                  timestamp with time zone,
    client_id                   uuid

);

-- Sync-pull keyset index (database-schema §4.2 / offline-sync §B.4 / data-model §5).
-- Same three-pattern coverage as ix_supply_items__sync_pull / ix_reminders__sync_pull.
-- Covers: (a) sync/pull steady-state delta, (b) GET /checklist-items cursor list,
--         (c) cold-start cursor continuation keyset > (updated_at, id).
-- INCLUDE (version) omitted for H2 compatibility.
CREATE INDEX ix_checklist_items__sync_pull ON checklist_items (user_id, updated_at, id);
