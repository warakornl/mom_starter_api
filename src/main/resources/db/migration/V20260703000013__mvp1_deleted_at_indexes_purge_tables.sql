-- mvp1 — deleted_at indexes on all PURGE_TABLES (design §2.4 / compliance-reviewer flag).
--
-- BACKGROUND:
--   TombstoneGcService.purgeExpiredTombstones() and AccountErasureService both run
--   DELETE queries that filter on `deleted_at IS NOT NULL AND deleted_at < ?`.
--   Without an index, every GC sweep full-scans each table — O(rows) per table per run.
--   This migration eliminates the technical debt BEFORE data grows.
--
-- H2 COMPATIBILITY NOTE — why plain indexes instead of partial:
--   In PostgreSQL (production), the ideal index is:
--     CREATE INDEX ... ON <table> (deleted_at) WHERE deleted_at IS NOT NULL;
--   A partial index skips live rows (deleted_at IS NULL — the vast majority), keeping
--   the index tiny and fast.  However, H2 PostgreSQL-mode in the version used by this
--   project does NOT recognise the WHERE clause on CREATE INDEX (syntax error in tests).
--   Consistent with other migrations in this project (see INCLUDE omitted from sync-pull
--   indexes for the same reason), we use a plain index here so migrations run on both H2
--   and PostgreSQL without modification.
--
--   PRODUCTION FOLLOW-UP (technical debt):
--     Before launch, rebuild each index on the live PostgreSQL database as a partial index:
--       DROP INDEX <name>;
--       CREATE INDEX <name> ON <table> (deleted_at) WHERE deleted_at IS NOT NULL;
--     This does NOT require a Flyway migration (it is an online index rebuild, not a DDL
--     change to the schema) and can be done with zero downtime using CREATE INDEX CONCURRENTLY.
--
-- TABLES COVERED (matching TombstoneGcService.PURGE_TABLES after Phase 3 update):
--   supply_items, reminders, reminder_occurrences, checklist_items,
--   kick_count_session, pregnancy_profile
--
-- REVERSIBILITY:
--   Forward-only (index creation).  Rollback: DROP each index by name.

CREATE INDEX ix_supply_items__deleted_at
    ON supply_items (deleted_at);

CREATE INDEX ix_reminders__deleted_at
    ON reminders (deleted_at);

CREATE INDEX ix_reminder_occurrences__deleted_at
    ON reminder_occurrences (deleted_at);

CREATE INDEX ix_checklist_items__deleted_at
    ON checklist_items (deleted_at);

CREATE INDEX ix_kick_count_session__deleted_at
    ON kick_count_session (deleted_at);

-- pregnancy_profile: added to PURGE_TABLES in this same Phase 3 slice.
-- EDD / birth_date are the most sensitive fields (PDPA ม.26) — efficient GC sweep
-- ensures soft-deleted profiles are hard-erased promptly when retention expires.
CREATE INDEX ix_pregnancy_profile__deleted_at
    ON pregnancy_profile (deleted_at);
