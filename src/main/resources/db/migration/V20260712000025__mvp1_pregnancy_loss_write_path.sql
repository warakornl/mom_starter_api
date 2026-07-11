-- mvp1 — pregnancy-loss write path: loss_date + reminder reversible-tombstone columns.
-- Data-model §5 hand-off "Pregnancy-loss write path" (data-model.md L263-286 / L510-516);
-- functional-spec pregnancy-loss-recording-functional-spec.md §6/§7.3/§7.4;
-- architecture technical-decisions.md §14 (14.3 reversible tombstone / 14.4 by-item allow-list).
--
-- ── SCOPE ────────────────────────────────────────────────────────────────────────────────────
-- lifecycle already supports 'ended' (CHECK added in V20260629000004) — no change needed there.
-- This migration adds ONLY the two physical pieces the loss/reopen write path needs:
--   (1) pregnancy_profile.loss_date        — the optional loss civil date.
--   (2) reminder.survives_ended / deactivated_by / deactivated_at — the reversible
--       soft-deactivation ("tombstone-style", NOT a deleted_at hard-delete, NOT a crypto-shred)
--       stamped by the loss-event sweep and cleared by the reopen sweep.
--
-- ── (1) pregnancy_profile.loss_date ──────────────────────────────────────────────────────────
-- date NULL — floating-civil DATE-ONLY (YYYY-MM-DD, zoneless, never UTC-normalized; same
-- FLAG-1 family as birth_date — date-granularity, no time component, no metadata: S6).
-- OPTIONAL/default-empty/skippable (data-model L275): the system needs only lifecycle='ended'
-- to stop content/push; the date itself is the mother's to withhold. Absent/omitted body on
-- loss-event → stored NULL (functional-spec §7.2) — a full success, never rejected.
-- Set by POST /pregnancy-profile/loss-event; cleared to NULL in the SAME transaction as
-- POST /pregnancy-profile/reopen (S4/ม.35/ม.36 — a stale loss-date on a now-pregnant profile
-- is inaccurate + purpose-less; cleared, not orphaned).
-- PDPA: PLAINTEXT — NOT on the encrypted-column list (data-model L511 / pdpa-assessment S6).
-- A bare date carries no time-of-day and no metadata; the hospital-stay/name ciphers in this
-- schema exist because THOSE fields carry more (a specific clinical event + free text) —
-- loss_date is deliberately excluded from that list by the architecture. Do not add a cipher
-- column here without a security-compliance ruling overriding data-model L511.
-- No DB CHECK for bounds — validation (loss_date_range / loss_date_malformed, functional-spec
-- §7.2) is application-layer relative to the client civil "today" and edd, same posture as
-- birth_date (V20260629000005).
-- LOSS-INV-10 / L-15.7 / Z-8 (permanent lock): loss_date/lifecycle must NEVER feed any
-- ad/product/targeting query — enforced at the application/query layer, not by the DB.
ALTER TABLE pregnancy_profile
    ADD COLUMN loss_date date NULL;

-- ── (2) reminder reversible-tombstone columns ────────────────────────────────────────────────
-- One ADD COLUMN per ALTER TABLE (H2 PostgreSQL-mode requirement — see V20260630000011 /
-- V20260710000022 house convention).
--
-- survives_ended: by-ITEM allow-list flag (AC-2.3 / LOSS-INV-5). Default false = "pregnancy
-- progress, deactivate on loss" (the safe default — Z-18: fail toward stopping pregnancy
-- content). NEVER inferred from `type` — precise clinical membership needs clinical +
-- security-compliance sign-off (flagged, not decided here; data-model L282).
ALTER TABLE reminders
    ADD COLUMN survives_ended boolean NOT NULL DEFAULT false;

-- deactivated_by: reversible-tombstone provenance. NULL = never swept by the loss path.
-- 'loss_event' = the exact and only value this MVP writes (stamped by the loss-event sweep,
-- cleared back to NULL by the reopen sweep). Left as free `text` (not a CHECK-constrained
-- enum) because it is a provenance marker, not a closed domain value in the sense of `status`/
-- `lifecycle` — mirrors data-model L512's plain `text NULL` typing. This column is the
-- SCOPING KEY for reopen's re-activation predicate (functional-spec §6.2 / §10.7): a reminder
-- the user themselves soft-deleted (`deleted_at`) is excluded from the loss sweep's ownership,
-- so it is never touched by reopen even if deactivated_by happens to still read 'loss_event'
-- from a prior sweep (the reopen predicate also requires deleted_at IS NULL — application-layer,
-- see hand-off note below).
ALTER TABLE reminders
    ADD COLUMN deactivated_by text NULL;

-- deactivated_at: absolute-UTC instant (timestamptz — the <sync> clock family, FLAG-1),
-- stamped now() by the loss-event sweep alongside deactivated_by, cleared to NULL by the
-- reopen sweep. NOT the floating-civil family (this is a system/action instant, not a
-- bucket-key event time — data-model §5 "Event-timestamp column types" carry-forward).
ALTER TABLE reminders
    ADD COLUMN deactivated_at timestamp with time zone NULL;

-- ── Index decision (data-model L516) ────────────────────────────────────────────────────────
-- The forward sweep filters (user_id, survives_ended=false, active=true, deleted_at IS NULL);
-- the reverse reactivation filters (user_id, deactivated_by='loss_event', deleted_at IS NULL).
-- At MVP reminder cardinality (tens per user) data-model explicitly anticipates NO dedicated
-- index is needed: the existing ix_reminders__sync_pull (user_id, updated_at, id) already
-- gives a user_id-rooted access path, and both sweep UPDATEs are single-user, low-cardinality
-- scans bounded by that user_id prefix — a residual filter over a few dozen rows is expected
-- sufficient (data-model L516 "confirm at your discretion").
--
-- Decision: NO new index added in this migration. Rationale mirrors V20260710000022's
-- care_activity_type precedent (no index for a low-cardinality per-user boolean/text filter).
-- Revisit if reminder cardinality per user grows materially beyond MVP expectations, or if
-- database-reviewer / production query-plan evidence (EXPLAIN ANALYZE on real data volume)
-- shows the residual filter is not index-only. Both predicates are still user_id-rooted, so a
-- production sequential scan is bounded to one user's reminder set, never the whole table.
--
-- ── Retention / auto-purge (S5) ──────────────────────────────────────────────────────────────
-- No auto-purge on lifecycle='ended' — retention is unchanged by this migration. The
-- loss_event deactivation is a reversible marker, NOT a deleted_at tombstone, and MUST stay
-- excluded from the existing 180-day tombstone-GC horizon (that GC keys off deleted_at only —
-- unaffected by this migration, since these three new columns are never read by the GC query).
--
-- ── Reversibility ────────────────────────────────────────────────────────────────────────────
-- Purely additive: two nullable/defaulted columns on reminders, one nullable column on
-- pregnancy_profile. No backfill, no rewrite-locking type change, no data loss on rollback.
-- Rollback (documented, not auto-applied — Flyway Community has no down-migration runner):
--   ALTER TABLE reminders DROP COLUMN deactivated_at;
--   ALTER TABLE reminders DROP COLUMN deactivated_by;
--   ALTER TABLE reminders DROP COLUMN survives_ended;
--   ALTER TABLE pregnancy_profile DROP COLUMN loss_date;
