-- mvp1 — kick_count_session ADDITIVE extension: target_count + status + gestational_week_at_start.
-- Extends the BASE table (V20260630000010__mvp1_kick_count_session_base) with the 3 kick-count
-- feature MVP columns.  Data-model §3.13 (OQ-K1 RESOLVED) + compliance K-2/K-5a/K-6/K-7.
--
-- ADDITIVE ONLY: ADD COLUMN — no type changes, no renames, no existing-column alterations.
-- All three new columns have safe DEFAULT values so the ALTER is metadata-only on PostgreSQL
-- (no table rewrite) and applies cleanly on an empty table in H2 / CI.
-- No record-class change: kick_count_session stays a create-only immutable-event collection
-- (union-merge, soft-delete via tombstone, no LWW on transient state).
--
-- ────────────────────────────────────────────────────────────────────────────────────────────
-- COLUMN DECISIONS (database-engineer owns these — data-model §5 directives)
-- ────────────────────────────────────────────────────────────────────────────────────────────
--
-- [1] target_count  integer NOT NULL DEFAULT 10  CHECK (target_count = 10)
--     MVP lock: Cardiff count-to-10 method (K-5a — no deadline, no window, count-only).
--     Stored as a PER-SESSION SNAPSHOT so a future default change (e.g. adjustable target)
--     does not retroactively alter past sessions (mirrors content_pack_version reproducibility,
--     data-model §3.13).
--     Config / descriptive-progress-aid value — PLAINTEXT-AT-REST acceptable (compliance §2.3).
--     NOT a pass/fail threshold (INV-K2); server NEVER interprets or compares it.
--     CHECK (target_count = 10): structural invariant that pins the MVP lock; relax additively
--     when an adjustable target ships (additive migration, DROP CONSTRAINT + new CHECK or remove).
--     NO target_window_minutes column (K-5a explicitly out of MVP; count-to-10 has no deadline;
--     re-add only when a windowed method ships, after re-reviewing threshold/verdict posture).
--
-- [2] status  text NOT NULL DEFAULT 'completed'  CHECK (status = 'completed')
--     Terminal-on-the-wire guard (data-model §3.13 / §4 "Immutable event logs").
--     The enum has three values for the LOCAL draft lifecycle: in_progress | completed | cancelled.
--     Only 'completed' is ever a PERSISTED/SYNCED server row (OQ-K1 RESOLVED).
--     The sync/push apply-path already rejects in_progress/cancelled as validation_error
--     (defense-in-depth — a buggy/future client cannot fork transient state into the cloud).
--     Choice made here: CHECK (status = 'completed') on the server table — makes the invariant
--     STRUCTURAL, consistent with the apply-path guard (data-model §5: "database-engineer picks
--     one approach and keeps it consistent with the apply-path guard").
--     This is analogous to the ReminderOccurrence 🟡-2 terminal-status guard (done/snoozed only
--     in the synced table, due/missed remain valid enum values for the local on-device store).
--     in_progress / cancelled remain valid enum values in the MOBILE local SQLite store (exactly
--     as due/missed are local-only states for reminder_occurrence — not stored here on the server).
--
-- [3] gestational_week_at_start  integer NULL
--     Derived snapshot of the gestational week at started_at.
--     CLIENT-SIDE computation via §3.1 canonical algorithm (golden test-vectors — client obligation).
--     DENORMALIZED: stored so history renders gestational context after birth
--     (forward computation is impossible once lifecycle = postpartum).
--     NOT user input.  DRIFT pin (DRIFT-1): server NEVER recomputes or validates this value
--     (no week-gate, no week-derive on push — stored verbatim, same posture as
--     supply_item.low_notified_at_version and duration_seconds).
--     Golden-vector conformance is a CLIENT test obligation (rn-mobile-dev), not server's.
--     NULLABLE-TOLERANT: a push with NULL gestational_week_at_start is ACCEPTED (no week-gate
--     on the server) so a missing-profile edge case never false-rejects a push.
--     In practice always populated: the module surfaces only at wk >= 32 (client UI gate).
--     Derived-context value — PLAINTEXT-AT-REST acceptable (compliance §2.3 — not a new
--     collection of personal data; derived from PregnancyProfile the app already has consent for).
--
-- ENCRYPTION POSTURE CONFIRMATION (K-2 — database-engineer confirms):
--   note_cipher (base table): client-encrypted bytea — UNCHANGED, already on encrypted-column
--     list (pdpa-assessment ruling 2). PDF gate: sensitive_lab_results / includeLab=true (K-7/Y1).
--   movement_count / duration_seconds (base table): PLAINTEXT-AT-REST under KMS volume encryption —
--     CONFIRMED CONSISTENT with self_log structured numeric values (compliance §2.3 / ruling 2.1).
--     Column-level envelope encryption is an optional additive future step; both tables aligned.
--   target_count / gestational_week_at_start (new): PLAINTEXT-AT-REST — config/derived-context
--     values, not sensitive in themselves (compliance §2.3).
--
-- RETENTION (K-6 — confirmed, no schema change needed):
--   All three new columns follow the SHARED MOTHER-HEALTH retention window already encoded in
--   the base table (tombstone GC 180 days + per-account DEK crypto-shred on DELETE /account).
--   No kick-count-specific retention policy is added; the central GC policy covers this table.
--
-- INDEX DECISION for new columns (no new index added):
--   target_count = constant (10) → no selective filter; an index would have near-zero selectivity.
--   status = constant ('completed') → same: no selective filter on a single-value column.
--   gestational_week_at_start = derived context; not a primary query dimension; residual filter
--     at MVP cardinality (a few sessions/user) is acceptable without a dedicated index.
--   History and sync-pull access patterns are fully covered by the indexes in V000010.
--
-- Reversibility: Rollback = DROP COLUMN target_count, status, gestational_week_at_start.
--   All three columns are purely additive; dropping them restores the V000010 baseline with
--   zero data loss on the retained columns (id, user_id, started_at, ended_at, duration_seconds,
--   movement_count, note_cipher, <sync>).

-- H2 (PostgreSQL-mode) requires one ADD COLUMN per ALTER TABLE statement.
-- PostgreSQL supports the multi-column form but H2 does not.
-- Three statements below are semantically equivalent to a single multi-column ALTER.

ALTER TABLE kick_count_session
    ADD COLUMN target_count integer NOT NULL DEFAULT 10
        CHECK (target_count = 10);

ALTER TABLE kick_count_session
    ADD COLUMN status text NOT NULL DEFAULT 'completed'
        CHECK (status = 'completed');

ALTER TABLE kick_count_session
    ADD COLUMN gestational_week_at_start integer NULL;
