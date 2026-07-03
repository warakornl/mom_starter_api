-- mvp1 — medication_plan + medication_log: structured drug-tracking (SD-2 health data).
-- ADR: docs/api-spec/adr/medication-encryption-and-schema.md (RULING 1–6).
-- database-schema.md §1.4 (reconciled against shipped codebase per RULING 6).
--
-- TWO TABLES — medication_plan MUST be created BEFORE medication_log because
-- medication_log.medication_plan_id → medication_plan(id) is a HARD FK (RULING 6).
--
-- ── medication_plan — MUTABLE record → LWW (sibling of expense / supply_items) ──────────────────
--   SD-2 health data: drug name implies a condition; specific dose can narrow drug/condition
--   (PDPA ม.26 / RULING 1).  name_cipher / dose_cipher are bytea, Option A — MVP holds PLAINTEXT
--   BYTES via a no-op / passthrough cipher; real AES-GCM lands in THE SAME COLUMNS at the
--   KMS/EAS milestone with ZERO schema and ZERO contract change (kick_count_session.note_cipher
--   pattern already merged and green — HANDOFF §3 / RULING 1).  Server NEVER parses or
--   aggregates any *_cipher value (G4 / INV-M3 / RULING 1).
--   Crypto-shred on tombstone: name_cipher + dose_cipher SET NULL in the same tombstone UPDATE
--   (database-schema §4.4(A) / PDPA ruling 5a).
--   ck_medication_plan__live_name guards name_cipher: a LIVE plan MUST carry a name;
--   only a tombstone may have it crypto-shredded to NULL (RULING 1 — first guarded live-cipher
--   CHECK to ship; no DROP NOT NULL retrofit needed because medication_plan is a brand-new table).
--   source_suggestion_state_id is a SOFT LINK (no FK) — see column comment below.
--
-- ── medication_log — IMMUTABLE event (sibling of self_log / kick_count_session) ─────────────────
--   Create-only union-merge; the only post-create mutation is the soft-delete tombstone.
--   occurrence_time : FLOATING-CIVIL (timestamp WITHOUT TIME ZONE — FLAG-1 / database-schema §0.2).
--     The calendar bucket key for GET /medication-logs?from=&to=; NEVER UTC-normalized.
--   logged_at       : ABSOLUTE-UTC record-creation instant (timestamptz; NOT the LWW clock — that
--     is updated_at; echoed on the response per contract MedicationLogInput).
--   medication_plan_id : nullable HARD FK → medication_plan(id); NULL = ad-hoc dose with no plan.
--     Drives Tier-1 erasure order: medication_log purged BEFORE medication_plan (RULING 4).
--   note_cipher     : optional bytea (Option A, same posture); crypto-shred to NULL on tombstone.
--
-- id is CLIENT-GENERATED (uuid v4) for both tables — NOT a DB DEFAULT (sync spec §A.4 /
--   data-model §2).  Re-push of the same id at the same version is an idempotent no-op.
--
-- FK reconciliation (RULING 6):
--   REFERENCES users(id) — physical table is `users`; `app_user` was never created
--   (every merged migration targets `users`; §1.4's `app_user` reference is stale — RULING 6).
--   No ON DELETE clause → RESTRICT.  Erasure is application-level: Tier-1 explicit child
--   purge (ม.33) + 180-day tombstone GC (database-schema §4.4 / RULING 4).
--
-- version DEFAULT 0 (RULING 5):
--   Pre-stamp safety net; JPA @Version stamps 1 on first apply.  database-schema §1.4 sketch
--   DEFAULT 1 is reconciled — observable version on any applied row is >= 1 either way
--   (engine parity with kick_count_session / expense / self_log).
--
-- TIER-1 ERASURE ORDER (RULING 4 — load-bearing):
--   AccountErasureService.TIER1_CHILD_DELETE_ORDER must append after "self_log":
--     "medication_log"   -- FK → users + FK → medication_plan; MUST precede medication_plan
--     "medication_plan"  -- FK → users; crypto-shred name_cipher/dose_cipher on purge
--   (Task 3 of this slice appends both entries + seeds AccountErasureServiceTest.)
--
-- ENCRYPTION POSTURE RESIDUAL RISK (RULING 1 / ADR PDPA callout):
--   At-rest protection for MVP relies on AWS RDS volume/disk encryption-at-rest, IAM
--   least-privilege, TLS in transit — NOT per-field column encryption.
--   Drug name/dose in medication_plan are higher-sensitivity SD-2 data than self_log (a drug
--   name directly implies a medical condition).  security-compliance acceptance + owner
--   acknowledgement required before production launch; tracked alongside the self_log deferral.
--
-- Reversibility:
--   Forward-only (new tables).
--   Rollback = DROP TABLE medication_log; DROP TABLE medication_plan;
--   (child before parent — same FK order as Tier-1 erasure)


-- ===== medication_plan — MUTABLE record → LWW (sibling of expense / supply_items). =====
-- SD-2 health data: drug name implies a condition; specific dose narrows drug/condition (PDPA ruling 4).
-- name_cipher / dose_cipher: bytea, Option A (MVP plaintext bytes via no-op cipher; real AES-GCM later,
--   SAME columns, zero schema/contract change — kick_count_session.note_cipher pattern). NEVER parsed.
--   Crypto-shred to NULL on tombstone (§4.4(A)). live rows guarded by ck_medication_plan__live_name.
CREATE TABLE medication_plan (

    -- PK: CLIENT-GENERATED uuid v4 (offline-safe, idempotent push). No DB DEFAULT.
    id                          uuid                        PRIMARY KEY,

    -- Owner. app_user -> users reconciliation: the real table is `users` (RULING 6).
    -- No ON DELETE clause → RESTRICT; erasure is application-level (Tier-1 child purge + tombstone GC).
    user_id                     uuid                        NOT NULL REFERENCES users (id),

    -- SD-2 ciphers (Option A; MVP plaintext bytes). NEVER queried server-side.
    -- name_cipher: nullable so a tombstone can be crypto-shredded; live rows require it (CHECK below).
    name_cipher                 bytea,
    -- dose_cipher: genuinely optional (a plan may record no dose); nullable, no guard; shreds freely.
    dose_cipher                 bytea,

    -- schedule_rule: FLAG-4 recurrence grammar as jsonb (nullable — a plan need not schedule reminders).
    -- Consistent with reminder.recurrence_rule (jsonb). H2 CAVEAT (memory h2-masks-jsonb-binding):
    -- backend maps this with @JdbcTypeCode(SqlTypes.JSON) + a smoke test on REAL Postgres — H2
    -- PostgreSQL-MODE accepts varchar->jsonb silently, so H2-only tests can mask a binding bug.
    schedule_rule               jsonb,

    -- active: plaintext boolean (non-sensitive UI/scheduling flag; does NOT bound adherence — RULING 7).
    active                      boolean                     NOT NULL DEFAULT true,

    -- SOFT LINK to the UserSuggestionState that spawned this plan (US-8/9 Start flow).
    -- NO FK: user_suggestion_state does NOT exist server-side in MVP (suggestion flow is
    -- client-side / SecureStore). NULL for user-created plans. Mirrors checklist_item.source_suggestion_state_id
    -- (V20260630000009, "SOFT LINK (no FK)"). Apply-path validates it if/when the server table lands (additive).
    source_suggestion_state_id  uuid,

    -- <sync> block (database-schema §0.3 / data-model §1.2).
    --   created_at  : server-assigned on first INSERT; never changed after.
    --   updated_at  : server-assigned on EVERY apply; the single LWW merge clock.
    --   version     : DEFAULT 0 = pre-stamp safety net; @Version stamps 1 on first apply (RULING 5).
    --   deleted_at  : soft-delete tombstone.  NULL = live.  NOT NULL = tombstone (tombstone-wins).
    --                 On tombstone: crypto-shred name_cipher + dose_cipher to NULL (§4.4(A)).
    --                 GC after TOMBSTONE_TTL = 180 days (database-schema §4.4).
    --   client_id   : originating device uuid; LWW tie-break ONLY (sync spec §A.6).  No FK.
    created_at                  timestamp with time zone    NOT NULL DEFAULT now(),
    updated_at                  timestamp with time zone    NOT NULL DEFAULT now(),
    version                     bigint                      NOT NULL DEFAULT 0,
    deleted_at                  timestamp with time zone,
    client_id                   uuid,

    -- A LIVE plan MUST carry a name; only a tombstone may have name_cipher crypto-shredded to NULL.
    -- This is the first guarded live-cipher CHECK to ship in the codebase (RULING 1 / ADR §scope note).
    -- No DROP NOT NULL retrofit step needed — medication_plan is a brand-new table.
    CONSTRAINT ck_medication_plan__live_name
        CHECK (deleted_at IS NOT NULL OR name_cipher IS NOT NULL)

);

-- Sync-pull keyset / steady-state delta (WHERE user_id=? AND updated_at>=watermark ORDER BY updated_at, id).
-- Also serves GET /medication-plans (read-only list; no domain-time filter — <sync> order is sufficient).
-- INCLUDE (version) omitted for H2 compatibility (same as every other sync-pull index in this codebase).
-- ADR RULING 6 / database-schema §4.2.
CREATE INDEX ix_medication_plan__sync_pull  ON medication_plan (user_id, updated_at, id);

-- Tombstone GC sweep: DELETE FROM medication_plan WHERE deleted_at IS NOT NULL AND deleted_at < ?
-- Plain index for H2 portability; PROD FOLLOW-UP: partial WHERE deleted_at IS NOT NULL (CONCURRENTLY).
-- ADR RULING 6 / database-schema §4.4.
CREATE INDEX ix_medication_plan__deleted_at ON medication_plan (deleted_at);


-- ===== medication_log — IMMUTABLE event (sibling of self_log / kick_count_session). =====
-- Create-only union-merge; only post-create mutation is the soft-delete tombstone.
CREATE TABLE medication_log (

    -- PK: CLIENT-GENERATED uuid v4. No DB DEFAULT.
    id                  uuid                        PRIMARY KEY,

    -- Owner. app_user -> users (RULING 6). No ON DELETE → RESTRICT; application-level erasure.
    user_id             uuid                        NOT NULL REFERENCES users (id),

    -- HARD FK to the plan (nullable = ad-hoc dose with no plan).
    -- Drives Tier-1 erasure order: medication_log MUST be purged BEFORE medication_plan (RULING 4).
    -- No ON DELETE cascade — erasure is application-level (same policy as every FK in this codebase).
    medication_plan_id  uuid                        REFERENCES medication_plan (id),

    -- occurrence_time: FLOATING-CIVIL bucket key (FLAG-1) — timestamp WITHOUT TIME ZONE; NEVER UTC-normalized.
    -- The calendar bucket the dose was taken/missed; GET /medication-logs?from=&to= filters on this.
    -- DATE part is the bucket key; intra-day ordering uses this column; cross-device merge uses updated_at.
    occurrence_time     timestamp without time zone NOT NULL,

    -- status: plaintext enum (query/routing label; NEVER interpreted as a health verdict — G4 / INV-M3).
    -- CHECK backstops the apply-path enum guard (same text+CHECK pattern as every enum — database-schema §0.2).
    status              text                        NOT NULL
        CHECK (status IN ('taken', 'missed')),

    -- logged_at: ABSOLUTE-UTC record-creation instant (timestamptz).
    -- NOT the floating-civil bucket key (that is occurrence_time) and NOT the LWW clock (that is updated_at).
    -- Echoed on the response (contract MedicationLogInput).
    logged_at           timestamp with time zone    NOT NULL DEFAULT now(),

    -- note_cipher: optional client-encrypted free-text (Option A; MVP plaintext bytes). NEVER parsed.
    -- Genuinely optional (no guard); crypto-shred to NULL on tombstone (§4.4(A)).
    note_cipher         bytea,

    -- <sync> block (database-schema §0.3 / data-model §1.2).
    --   created_at  : server-assigned on first INSERT; never changed after.
    --   updated_at  : server-assigned on EVERY apply; the single LWW merge clock.
    --                 For an immutable event this is bumped only on tombstone
    --                 (re-push at same version is an idempotent no-op — sync §A.8).
    --   version     : DEFAULT 0 = pre-stamp safety net; @Version stamps 1 on first apply (RULING 5).
    --   deleted_at  : soft-delete tombstone.  NULL = live.  NOT NULL = tombstone (tombstone-wins).
    --                 On tombstone: crypto-shred note_cipher to NULL (§4.4(A)).
    --                 GC after TOMBSTONE_TTL = 180 days (database-schema §4.4).
    --   client_id   : originating device uuid; LWW tie-break ONLY.  No FK.
    created_at          timestamp with time zone    NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone    NOT NULL DEFAULT now(),
    version             bigint                      NOT NULL DEFAULT 0,
    deleted_at          timestamp with time zone,
    client_id           uuid

);

-- Sync-pull keyset / steady-state delta.
-- Covers: (a) sync/pull delta WHERE user_id=? AND updated_at>=watermark ORDER BY updated_at, id
--         (b) cold-start keyset continuation AND (updated_at > ? OR (updated_at = ? AND id > ?))
-- INCLUDE (version) omitted for H2 compatibility.
-- ADR RULING 6 / database-schema §4.2.
CREATE INDEX ix_medication_log__sync_pull   ON medication_log (user_id, updated_at, id);

-- GET /medication-logs history/range — ORDER BY occurrence_time DESC, id DESC keyset.
-- Leading user_id scopes to one user; occurrence_time DESC + id DESC = keyset direction
-- so the composite IS the query order (no sort node).
-- database-schema §4.2 names this index explicitly with DESC (contrast self_log ASC — both serve
-- the same DESC cursor; following the schema doc's spelling for byte-for-byte consistency).
-- ADR RULING 6 / database-schema §4.2.
CREATE INDEX ix_medication_log__user_time   ON medication_log (user_id, occurrence_time DESC, id DESC);

-- FK-join index: medication_plan_id drives plan→logs lookup AND the Tier-1 erasure
-- DELETE-by-plan path; without it those are seq-scans (database-schema §7 FK-join rule).
-- Also supports the future "list logs for a plan" read pattern efficiently.
-- ADR RULING 6.
CREATE INDEX ix_medication_log__plan        ON medication_log (medication_plan_id);

-- Tombstone GC sweep: DELETE FROM medication_log WHERE deleted_at IS NOT NULL AND deleted_at < ?
-- Plain index for H2 portability; PROD FOLLOW-UP: partial WHERE deleted_at IS NOT NULL (CONCURRENTLY).
-- ADR RULING 6 / database-schema §4.4.
CREATE INDEX ix_medication_log__deleted_at  ON medication_log (deleted_at);
