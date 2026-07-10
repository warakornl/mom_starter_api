-- mvp1 — supply_items updated_at/version sync-guard trigger (INV-ASD-8, covert-channel closure).
-- ASD carry-forward (data-model §3.14 / §5 item 6 / auto-stock-decrement-architecture §5.1 /
--   pdpa-assessment INV-ASD-8 / database-schema §0.3).
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- LOCATION: db/pg-only/ (PostgreSQL-ONLY — PL/pgSQL; NOT run against H2)
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- This migration uses PL/pgSQL (LANGUAGE plpgsql), which is NOT supported by H2 PostgreSQL
-- mode.  It lives in src/main/resources/db/pg-only/ and is intentionally excluded from
-- H2 / @DataJpaTest runs via Flyway location configuration:
--
--   Profiles that reach real PostgreSQL (application.yml, application-local.yml):
--     spring.flyway.locations: classpath:db/migration,classpath:db/pg-only
--
--   H2 / test profile (application-test.yml):
--     spring.flyway.locations: classpath:db/migration
--
-- This is Option B from the original comment — the physical-folder approach is the only
-- reliable mechanism in Flyway Community Edition (the `!`-negation / exclusion syntax in
-- spring.flyway.locations is NOT supported in Community; always use a separate folder).
--
-- PRIMARY CONTROL (by construction — H2 tests rely on this, not on the trigger):
--   The PRIMARY covert-channel control is the ABSENCE of uses_remaining_in_open_container
--   from the server schema.  Since that column does not exist in supply_items on the server,
--   no per-scoop mutation can ever reach the server, and updated_at / version cannot be
--   bumped by a per-scoop draw.  H2 tests that assert INV-ASD-8 should test:
--     (a) supply_items has no uses_remaining_in_open_container column (schema assertion).
--     (b) supply_items has no feeding_session_id / fed_at / activity-linkage column (INV-ASD-4).
--     (c) after N formula feeds within one container, supply_items.updated_at and .version
--         are NOT bumped (end-to-end test through the sync apply path on H2).
--   The trigger adds defense-in-depth at the DB layer but H2 tests do not depend on it.
--
-- REQUIRED SMOKE TEST BEFORE EACH RELEASE (real PostgreSQL 15+):
--   Run the QA smoke scenario in the "Verification note" section at the bottom of this file.
--   The trigger does not execute in the H2 @DataJpaTest suite; real-PG verification is
--   mandatory before releasing any change that touches supply_items sync or ASD logic.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- DESIGN: which columns the trigger watches (INV-ASD-8 BINDING)
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- SYNCED columns (LWW on server updated_at / version) — WATCHED by this trigger:
--   name                     — item label (user-entered; mutable)
--   category                 — diapers | feeding | hygiene | health-supplies | other
--   unit                     — free-text label (pcs/pack/tin), nullable
--   on_hand_qty              — container_count (the ONLY quantity that egresses per INV-ASD-8)
--   low_threshold            — nullable int; unset = no low-supply alert
--   low_notified_at_version  — cross-device de-nag marker; ordinary LWW field
--   uses_per_container       — static config (container-holds-N); SYNCED (ASD §3.1 / §5.1)
--   deleted_at               — soft-delete tombstone; tombstoning IS a SYNCED event
--
-- ON-DEVICE-ONLY column — absent from this server schema (primary control):
--   uses_remaining_in_open_container  [NOT IN THIS TABLE — enforced by absence]
--
-- Other <sync> / metadata columns NOT watched (changes to them alone do NOT bump):
--   client_id   — tie-break only; client may re-push the same client_id, not a content change
--   created_at  — immutable after INSERT; never changes
--   (updated_at and version are the OUTPUT of this trigger, not inputs to it)
--
-- TRIGGER SEMANTICS (BEFORE UPDATE):
--   If ALL SYNCED columns (name, category, unit, on_hand_qty, low_threshold,
--   low_notified_at_version, uses_per_container, deleted_at) are UNCHANGED from OLD to NEW:
--     -> Revert NEW.updated_at to OLD.updated_at (cancels any JPA @PreUpdate stamp).
--     -> Revert NEW.version to OLD.version (cancels any JPA @Version increment).
--   If ANY SYNCED column changed: pass through unchanged (normal LWW stamping proceeds).
--
--   IS NOT DISTINCT FROM: handles NULL equality correctly (NULL = NULL in this context).
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- SPRINGBOOT-BACKEND-DEV HAND-OFF: @Version OPTIMISTIC-LOCK CONSTRAINT (Gap-2)
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- This trigger reverts NEW.version := OLD.version when no SYNCED column changed.
-- That reversion is INCOMPATIBLE with calling JPA repository.save() on a supply_items entity
-- through the @Version path without also changing at least one SYNCED column.
--
-- If the server calls save() and the @Version field was incremented by Hibernate (as it
-- normally would be on any save), but the trigger reverts the DB version back to OLD.version,
-- then the next Hibernate flush on the SAME entity will see:
--   expected version = OLD.version + 1 (what Hibernate holds in memory)
--   actual DB version = OLD.version    (what the trigger reverted to)
-- This mismatch throws OptimisticLockException on the next access to that entity.
--
-- CONSTRAINT (binding on springboot-backend-dev):
--   The server MUST NEVER call save() on a supply_items entity via the @Version / JPA path
--   unless at least one SYNCED column (name, category, unit, on_hand_qty, low_threshold,
--   low_notified_at_version, uses_per_container, deleted_at) is also being changed in that
--   same transaction.
--
-- WHY THIS IS SAFE IN THE INTENDED DESIGN:
--   The only server-side mutation path for supply_items is the sync/push apply path.
--   That path updates a SYNCED column (e.g. on_hand_qty on container transition, or deleted_at
--   on tombstone push, or name/category/unit/low_threshold on user edit).
--   A per-scoop mutation that changes ONLY uses_remaining_in_open_container (on-device-only,
--   absent from the server table) MUST NOT reach the server at all — the mobile sync
--   serializer must not enqueue a push for such an event (see V20260710000021 SYNC SERIALIZER
--   NOTE).  Therefore, under the intended design, the server never saves supply_items without
--   a SYNCED column change, and this trigger never reverts version in a live code path.
--
-- DEFENSE-IN-DEPTH NOTE:
--   The trigger exists to block hypothetical future code paths that could accidentally touch
--   supply_items without a SYNCED column change (e.g. a background job that writes metadata
--   only).  Any such path would silently "succeed" at the DB level (no exception from the
--   trigger itself) but the version reversion would cause OptimisticLockException on the
--   caller's next access.  Treat that exception as a signal that a new code path is violating
--   the no-unsyced-save rule — fix the caller, do not work around the trigger.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- REVERSIBILITY
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- Rollback:
--   DROP TRIGGER IF EXISTS trg_supply_items_sync_guard ON supply_items;
--   DROP FUNCTION IF EXISTS fn_supply_items_sync_guard();
-- Dropping the trigger restores the pre-ASD behaviour (JPA @PreUpdate stamps every save).
-- The column addition in V20260710000021 is unaffected by dropping this trigger.

-- ─── Function ──────────────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION fn_supply_items_sync_guard()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    -- Guard: only bump updated_at / version when a SYNCED column actually changed.
    -- List EVERY SYNCED column explicitly so a future new SYNCED column forces a conscious
    -- update of this trigger (defense-in-depth audit trail).
    IF  (NEW.name                    IS NOT DISTINCT FROM OLD.name)
    AND (NEW.category                IS NOT DISTINCT FROM OLD.category)
    AND (NEW.unit                    IS NOT DISTINCT FROM OLD.unit)
    AND (NEW.on_hand_qty             IS NOT DISTINCT FROM OLD.on_hand_qty)
    AND (NEW.low_threshold           IS NOT DISTINCT FROM OLD.low_threshold)
    AND (NEW.low_notified_at_version IS NOT DISTINCT FROM OLD.low_notified_at_version)
    AND (NEW.uses_per_container      IS NOT DISTINCT FROM OLD.uses_per_container)
    AND (NEW.deleted_at              IS NOT DISTINCT FROM OLD.deleted_at)
    THEN
        -- No SYNCED column changed.  Revert any timestamp / version bump.
        -- This prevents a hypothetical per-scoop mutation (or any future path that updates
        -- supply_items without changing a SYNCED column) from creating a cadence leak via
        -- the sync-metadata covert channel (INV-ASD-8, pdpa-assessment §3 / §4).
        NEW.updated_at := OLD.updated_at;
        NEW.version    := OLD.version;
    END IF;

    RETURN NEW;
END;
$$;

-- ─── Trigger ───────────────────────────────────────────────────────────────────────────────────

CREATE TRIGGER trg_supply_items_sync_guard
    BEFORE UPDATE
    ON supply_items
    FOR EACH ROW
EXECUTE FUNCTION fn_supply_items_sync_guard();

-- ─── Verification note ─────────────────────────────────────────────────────────────────────────
--
-- QA smoke test on real PostgreSQL (INV-ASD-8 assertion):
-- REQUIRED before each release (this trigger does not run in H2 @DataJpaTest).
--
--   INSERT a supply_items row with uses_per_container = 10, on_hand_qty = 3.
--
--   Step 1: simulate N in-container scoops (no SYNCED column changes):
--     UPDATE supply_items
--        SET client_id = gen_random_uuid()   -- only client_id changes (tie-break, not SYNCED)
--      WHERE id = '<row>';
--   ASSERT: updated_at and version are UNCHANGED (trigger reverted the JPA stamp).
--
--   Step 2: simulate a container transition (on_hand_qty changes):
--     UPDATE supply_items SET on_hand_qty = 2 WHERE id = '<row>';
--   ASSERT: updated_at IS NOW() (within a few ms), version = old_version + 1.
--           This is the one container-level push that INV-ASD-8 accepts as the "ceiling".
--
-- This smoke test must be run on PostgreSQL 15+ before each release.  The primary control
-- (column absence) is H2-testable; this trigger is the DB-layer defense-in-depth layer.
