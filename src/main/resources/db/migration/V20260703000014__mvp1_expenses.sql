-- mvp1 — expenses: personal budgeting / spending tracker (NON-health, expenses slice).
-- Offline-first mutable LWW record. UI spec: expenses-ui.md. Feature: expenses-feature.md.
--
-- Domain boundary (expenses-ui.md §0):
--   expense records *what a mother spent* — never stores, interprets, or reacts to a health value.
--   'healthcare' is a SPENDING LABEL only (฿800, healthcare = ฿800 spending, NOT a health record).
--   Non-health personal-financial data: standard KMS-at-rest, NO field-level encryption,
--   NO new consent type. Gated by cloud_storage consent (whole-batch) ONLY.
--   CONFIRMED: mirrors supply_items security posture (security-compliance boundary).
--
-- ARCHITECTURE GAP (flagged for solution-architect review before production):
--   expenses-ui.md §8 (EX-1/EX-2) describes amount and note as client-encrypted.
--   This migration stores both as plaintext under at-rest KMS volume encryption, consistent with
--   the task directive ("store as integer satang") and the supply_items precedent for non-health data.
--   If field-level encryption is required for amount or note, the columns must change to bytea
--   and the sync engine must handle cipher bytes. Architect must confirm before mobile team builds
--   client-side encryption for these fields.
--
-- id is CLIENT-GENERATED (uuid v4) — NOT a DB DEFAULT.
--   The sync engine requires the client to supply the id so offline creates are globally unique
--   and push is idempotent (data-model §2 / offline-sync §A.4).
--
-- amount: stored in SATANG (Thai minor units, 1 satang = 0.01 baht) as a non-negative integer.
--   Avoids floating-point drift (architect note §3.4). Range: 0..MAX_INT (never negative).
--   CHECK (amount >= 0) is the DB backstop; the sync apply path validates before persisting.
--   Example: ฿590.00 → amount = 59000 satang.
--
-- incurred_on: floating-civil DATE bucket key (expenses-ui.md §3.1).
--   DB type: date (no time component). Stored as the user-entered civil date.
--   Decides which civil month's total this expense counts toward.
--   NEVER shifts across time zones (civil bucket key — same pattern as started_at in kick_count_session).
--
-- note: optional free-text, stored verbatim, NEVER parsed (EX-2 intent preserved: server never
--   interprets or analyses note content). Client-side "kept on device" framing is a UI label;
--   at-rest protection is KMS volume encryption (same as supply_items.name).
--
-- updated_at / version stamping:
--   JPA @Version (app-level via @PrePersist / @PreUpdate) is the primary authority.
--   DB DEFAULT now() / DEFAULT 0 are safety nets for out-of-band inserts only.
--
-- Reversibility:
--   Forward-only (new table). Rollback = DROP TABLE expenses CASCADE.

CREATE TABLE expenses (

    -- PK: CLIENT-GENERATED uuid v4 (offline-safe, idempotent push — sync spec §A.4).
    id                  uuid                     PRIMARY KEY,

    -- Owner. NOT NULL; every expense belongs to exactly one user.
    user_id             uuid                     NOT NULL REFERENCES users (id),

    -- amount: spending amount in satang (1 baht = 100 satang). Non-negative integer.
    -- CHECK is the DB backstop; the sync apply path clamps/validates before persisting.
    amount              integer                  NOT NULL
        CHECK (amount >= 0),

    -- category: fixed 5-enum (expenses-feature §3.2 / expenses-ui.md §3.3).
    -- Hyphenated keys are intentional (stable identifiers per the feature spec).
    -- CHECK instead of native PG enum for reversible Flyway migrations (database-schema §0.2).
    category            text                     NOT NULL
        CHECK (category IN ('baby-supplies', 'healthcare', 'baby-gear', 'mother', 'other')),

    -- incurred_on: floating-civil date bucket key (expenses-ui.md §3.1).
    -- DB type: date (no time component). The civil calendar day on which the expense occurred.
    -- Determines which month's total this counts toward.
    incurred_on         date                     NOT NULL,

    -- note: optional free-text, verbatim storage, NEVER parsed (EX-2 posture).
    -- Language-agnostic; echoed back to the client only. NON-health → plaintext at rest.
    note                text,

    -- <sync> block (data-model §1.2 / §2, database-schema §0.3):
    --   created_at : server-assigned on first insert (never changed after).
    --   updated_at : server-assigned on EVERY apply; the single LWW merge clock.
    --   version    : server-assigned, monotonic optimistic-concurrency token.
    --                @Version in the JPA entity manages this; Hibernate inserts 0 on first persist.
    --   deleted_at : soft-delete tombstone. NULL = live. NOT-NULL = tombstone (tombstone-wins, §4.1).
    --                180-day GC (TOMBSTONE_TTL) per database-schema §4.4.
    --   client_id  : originating device uuid; LWW tie-break ONLY (data-model §2 / offline-sync §A.6).
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version             BIGINT                   NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMP WITH TIME ZONE,
    client_id           UUID

);

-- Sync-pull keyset index (database-schema §4.2 / offline-sync §B.4 / data-model §5).
--
-- Covers three access patterns:
--   (a) sync/pull steady-state delta:
--         WHERE user_id = ? AND updated_at >= (watermark - safeWindow) ORDER BY updated_at, id
--   (b) GET /expenses cursor list (same keyset order).
--   (c) Cold-start drain keyset continuation:
--         AND (updated_at > cursorUpdatedAt OR (updated_at = cursorUpdatedAt AND id > cursorId))
--
-- INCLUDE (version) intentionally omitted for H2 compatibility (same as all other sync-pull indexes).
CREATE INDEX ix_expenses__sync_pull ON expenses (user_id, updated_at, id);

-- deleted_at index — efficient tombstone GC sweep (consistent with V20260703000013 pattern).
-- TombstoneGcService.purgeExpiredTombstones() runs:
--   DELETE FROM expenses WHERE deleted_at IS NOT NULL AND deleted_at < ?
-- Without this index the GC sweep would full-scan the table (O(rows) per run).
--
-- Plain index (not partial) for H2 compatibility — same rationale as V20260703000013.
-- PRODUCTION FOLLOW-UP: rebuild as partial index WHERE deleted_at IS NOT NULL (online, CONCURRENTLY).
CREATE INDEX ix_expenses__deleted_at ON expenses (deleted_at);
