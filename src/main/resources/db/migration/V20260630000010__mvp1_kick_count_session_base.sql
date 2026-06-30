-- mvp1 — kick_count_session: base table (นับลูกดิ้น, US-6 / US-K1–K8, SD-3, data-model §3.2).
-- Capture type (a): structured tracking module — IMMUTABLE EVENT (create-on-finalize).
-- MOTHER-health collection.  Gated by general_health (per-collection) + cloud_storage (whole-batch)
-- + email_verified (§4 / contract "Consent gating").  No new consent type (compliance K-1).
--
-- OQ-K1 RESOLVED (data-model §3.13): in_progress = LOCAL-ONLY draft, never pushed.
--   Only 'completed' sessions are ever a persisted/synced server row.
--   The sync/push apply-path rejects in_progress/cancelled with validation_error.
--   cancelled = discard draft, no event persisted.
--
-- id is CLIENT-GENERATED (uuid v4) — NOT a DB DEFAULT (sync spec §A.4 / data-model §2).
--
-- started_at / ended_at are FLOATING-CIVIL (timestamp WITHOUT TIME ZONE — FLAG-1).
--   started_at is the calendar BUCKET KEY for daily grouping in history (data-model §3.2 / §5).
--   Never UTC-normalized; DATE part is the bucket; consistent with medication_log.occurrence_time
--   and feeding_session.started_at (FLAG-1 carry-forward, data-model §5).
--   ended_at: floating-civil end wall-clock; always set on a completed (persisted) row.
--
-- duration_seconds: CLIENT-COMPUTED, stored VERBATIM.
--   The server NEVER recomputes from started_at/ended_at (DRIFT-1 — immutable event, data-model §3.13).
--
-- ENCRYPTION POSTURE (compliance K-2 / data-model §3.13 / pdpa-assessment ruling 2.1):
--   note_cipher  → client-encrypted bytea (AES-GCM + per-account DEK).  Free-text, NEVER parsed.
--                  PDF inclusion: sensitive_lab_results gate opt-in includeLab=true (K-7 / Y1).
--                  Crypto-shred on soft-delete + per-account DEK shred on DELETE /account (ruling 5).
--   movement_count / duration_seconds → PLAINTEXT-AT-REST under KMS volume encryption.
--                  Structured health values; at-rest posture CONSISTENT with self_log numeric values
--                  (compliance §2.3 / pdpa ruling 2.1: plaintext-at-rest under volume encryption today;
--                  optional column-envelope as defense-in-depth is an additive future step).
--                  Server NEVER interprets movement_count or duration_seconds (INV-K1/K2/G4).
--
-- RETENTION (K-6 / data-model §3.13):
--   kick_count_session follows the SHARED MOTHER-HEALTH retention window in the central GC policy
--   (same as medication_log, feeding_session, self_log — NOT a kick-count-specific window).
--   Tombstone GC = TOMBSTONE_TTL = 180 days (offline-sync §A.5, database-schema §4.4).
--   Account deletion → per-account DEK crypto-shred immediately (pdpa ruling 5b).
--   READ-ONLY history after birth (US-K7) is bounded by this shared retention window — not "forever".
--
-- K-7 (Y1 — PDF gate): descriptive values (movement_count, duration_seconds, started_at, ended_at)
--   are included in the doctor PDF under pdf_egress + general_health.
--   note_cipher is included ONLY when includeLab=true (sensitive_lab_results opt-in gate).
--   The PDF query MUST NOT bypass this gate (no kick-count-specific carve-out — same rule as
--   every other note_cipher column, pdpa-assessment ruling 2).
--
-- Reversibility: Forward-only (new table). Rollback = DROP TABLE kick_count_session.

CREATE TABLE kick_count_session (

    -- PK: CLIENT-GENERATED uuid v4.  No GENERATED / DEFAULT: server MUST NOT mint this id.
    -- Immutable-event re-send with the same id is an idempotent no-op (sync spec §A.8).
    id                          uuid                        PRIMARY KEY,

    -- Owner.  NOT NULL; every session belongs to exactly one user.
    user_id                     uuid                        NOT NULL REFERENCES users(id),

    -- started_at: floating-civil calendar BUCKET KEY (FLAG-1, data-model §3.2 / §5).
    -- DB type: timestamp WITHOUT TIME ZONE — never UTC-normalized (civil wall-clock).
    -- The DATE part groups sessions by calendar day in history (US-K7) and the PDF.
    -- Format (app-enforced): YYYY-MM-DDTHH:mm:ss at second precision.
    started_at                  timestamp without time zone NOT NULL,

    -- ended_at: floating-civil end wall-clock (FLAG-1).
    -- Always set on a persisted (completed) row — the session was finalized before push.
    -- DB type: timestamp WITHOUT TIME ZONE — never UTC-normalized.
    ended_at                    timestamp without time zone NOT NULL,

    -- duration_seconds: elapsed counting time in whole seconds.
    -- CLIENT-COMPUTED and stored VERBATIM — the server NEVER recomputes from started_at/ended_at
    -- (DRIFT-1 — immutable event, data-model §3.13).  Always set on a completed session.
    duration_seconds            integer                     NOT NULL,

    -- movement_count: number of fetal movements tapped by the user in this session.
    -- Structured health value — PLAINTEXT-AT-REST under KMS volume encryption
    -- (consistent with self_log numeric values, compliance §2.3 / pdpa ruling 2.1).
    -- NOT NULL on a completed (persisted) row.
    -- Server NEVER interprets or compares this value (INV-K1/K2 — no verdict, no threshold).
    -- Can be 0..N; finishing before target_count is identical to finishing at target (INV-K2).
    movement_count              integer                     NOT NULL,

    -- note_cipher: optional client-encrypted free-text note (bytea).
    -- Encryption: AES-GCM with per-account DEK (pdpa-assessment ruling 2 / 4).
    -- NULL when the user did not enter a note.
    -- NEVER parsed server-side; echoed back to the client only.
    -- PDF gate: included under sensitive_lab_results opt-in (K-7 / Y1 — data-model §3.13).
    -- Crypto-shred: zero-out/overwrite on soft-delete (deleted_at set) so the tombstone
    --   row retains no recoverable plaintext; per-account DEK shred on DELETE /account.
    note_cipher                 bytea,

    -- <sync> block (data-model §1.2 / §2 / database-schema §0.3).
    --   created_at  : server-assigned on first INSERT; never changed after.
    --   updated_at  : server-assigned on EVERY apply; immutable-event: bumped only on tombstone
    --                 (a re-push of the same id at the same version is a no-op — sync §A.8).
    --   version     : server-assigned monotonic optimistic-concurrency token (@Version Long).
    --                 DB DEFAULT 0 is a safety net; the apply path always stamps explicitly.
    --                 Create sentinel: base version absent/0 → server sets version:=1 (sync §4).
    --   deleted_at  : soft-delete tombstone.  NULL = live.  NOT NULL = tombstone (tombstone-wins,
    --                 offline-sync §A.5).  GC after TOMBSTONE_TTL (180 days, database-schema §4.4).
    --                 On tombstone: crypto-shred note_cipher before/at GC (pdpa ruling 5a).
    --   client_id   : originating device uuid; LWW tie-break ONLY (sync spec §A.6).
    --                 No FK — multi-device, device rows are not managed here.
    created_at                  timestamp with time zone    NOT NULL DEFAULT now(),
    updated_at                  timestamp with time zone    NOT NULL DEFAULT now(),
    version                     bigint                      NOT NULL DEFAULT 0,
    deleted_at                  timestamp with time zone,
    client_id                   uuid

);

-- Sync-pull keyset index (database-schema §4.2 / offline-sync §B.4 / data-model §5).
-- Same three-pattern coverage as ix_checklist_items__sync_pull / ix_reminders__sync_pull.
-- Covers:
--   (a) sync/pull steady-state delta:
--         WHERE user_id = ? AND updated_at >= (watermark - safeWindow) ORDER BY updated_at, id
--   (b) GET /kick-count-sessions cursor list (same keyset order).
--   (c) Cold-start drain keyset continuation:
--         AND (updated_at > cursorUpdatedAt OR (updated_at = cursorUpdatedAt AND id > cursorId))
-- INCLUDE (version) intentionally omitted for H2 compatibility (same as every other sync-pull index).
CREATE INDEX ix_kick_count_session__sync_pull ON kick_count_session (user_id, updated_at, id);

-- History/range index (calendar history view, PDF range scan — data-model §3.13 / §5).
-- Covers: GET /kick-count-sessions?from=&to= range query keyed on started_at (floating-civil bucket key).
-- started_at is the calendar-day grouping key for the history list (US-K7) and the PDF.
-- Also satisfies the per-function-commits trace: "query ประวัติ by user + time".
-- Residual filter on deleted_at (live rows only) is intentionally left as a post-filter —
-- kick-count cardinality per user is low (a few sessions per day) and a partial index
-- would reduce H2 portability without measurable gain.
CREATE INDEX ix_kick_count_session__history ON kick_count_session (user_id, started_at, id);
