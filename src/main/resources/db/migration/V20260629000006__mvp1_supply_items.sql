-- mvp1 — supply_items: household/convenience supply tracking (NON-health, supplies slice #1).
-- First entity wired end-to-end with the offline-sync engine (data-model §3.9, OQ-SYNC-18).
--
-- Domain boundary (data-model §3.9):
--   supply_item records *how much of a thing is on hand* — never stores, interprets, or reacts
--   to a health value. 'health-supplies' is a grouping label for consumables, NOT a health record.
--   Light personal data: standard KMS-at-rest only, NO field-level client encryption,
--   NO new consent type. Gated by cloud_storage consent (whole-batch) ONLY.
--   CONFIRMED by security-compliance. Mutable record → LWW on server updated_at + optimistic version.
--
-- id is CLIENT-GENERATED (uuid v4) — NOT a DB DEFAULT. The sync engine requires the client to
-- supply the id so offline creates are globally unique and push is idempotent (data-model §2 /
-- offline-sync §A.4). No gen_random_uuid() or GENERATED here.
--
-- updated_at / version stamping:
--   JPA @Version (app-level via @PrePersist / @PreUpdate) is the primary authority.
--   The DB DEFAULT now() on updated_at and DEFAULT 0 on version are safety nets for
--   out-of-band inserts only; the apply path always stamps explicitly. A §4.3 trg_sync_stamp
--   trigger is NOT added here (consistent with this codebase's existing pattern — see
--   V20260629000004). The app-layer @PreUpdate enforces server-clock authority.
--
-- Reversibility:
--   Forward-only migration (no data in flight; table is new). Rollback = DROP TABLE supply_items.

create table supply_items (

    -- PK: CLIENT-GENERATED uuid v4 (offline-safe, idempotent push — sync spec §A.4).
    -- No GENERATED / DEFAULT: the server MUST NOT mint this id; the client supplies it before push.
    id                      uuid                     primary key,

    -- Owner (FK → users.id). NOT NULL; every supply item belongs to exactly one user.
    user_id                 uuid                     not null references users (id),

    -- name: free-text, language-agnostic, stored verbatim, NEVER parsed.
    -- NON-health → plaintext (no _cipher column). security-compliance CONFIRMED.
    name                    text                     not null,

    -- category: fixed enum (data-model §3.9 / offline-sync-engine §C.2, source of truth).
    -- CHECK instead of native PG enum for reversible Flyway migrations (database-schema §0.2).
    category                text                     not null
        check (category in ('diapers', 'feeding', 'hygiene', 'health-supplies', 'other')),

    -- unit: free-text label (pcs / pack / tin), nullable, non-sensitive.
    unit                    text,

    -- on_hand_qty: current quantity (mutable). Clamped at 0, NEVER negative.
    -- Server clamps on apply (silent, not a validation_error) — offline-sync §A.7 / §C.2 / E10.
    -- DB CHECK is the backstop; the apply path clamps before persisting.
    on_hand_qty             integer                  not null default 0
        check (on_hand_qty >= 0),

    -- low_threshold: nullable; unset (NULL) ⇒ item never raises a low-supply alert (data-model §3.9).
    low_threshold           integer
        check (low_threshold >= 0),

    -- low_notified_at_version: cross-device de-nag marker (data-model §3.9 / offline-sync §C.2).
    --   ORDINARY LWW field — server does NOT recompute or validate it (contrast reminder_occurrence.id).
    --   NULL  ⇒ no outstanding low-notification for the current low-episode.
    --   NOT-NULL ⇒ this low-episode has already been alerted; stored int = version at alert-time.
    --   No MVP logic reads the stored int's value — suppression keys ONLY off NULL vs NOT-NULL.
    --   Type: integer (not bigint). Per-row version realistically never approaches int range
    --   (2.1B edits to one row is impossible). Promote to bigint only if a future episode-ordering
    --   refinement begins comparing this value arithmetically. (database-schema §1.11 type note.)
    low_notified_at_version integer,

    -- <sync> block (data-model §1.2 / §2, database-schema §0.3):
    --   created_at : server-assigned on first insert (never changed after).
    --   updated_at : server-assigned on EVERY apply; the single LWW merge clock (not client-settable).
    --   version    : server-assigned, monotonic optimistic-concurrency token.
    --                @Version in the JPA entity manages this; Hibernate inserts 0 on first persist.
    --   deleted_at : soft-delete tombstone. NULL = live. NOT-NULL = tombstone (tombstone-wins, §4.1).
    --                180-day GC (TOMBSTONE_TTL) per database-schema §4.4. No *_cipher columns here,
    --                so the crypto-shred sub-step is a no-op for this table (non-health, §1.11).
    --   client_id  : originating device uuid; LWW tie-break ONLY (data-model §2 / offline-sync §A.6).
    created_at              timestamp with time zone not null default now(),
    updated_at              timestamp with time zone not null default now(),
    version                 bigint                   not null default 0,
    deleted_at              timestamp with time zone,
    client_id               uuid

);

-- Sync-pull keyset index (database-schema §4.2 / offline-sync §B.4 / data-model §5).
--
-- Covers three access patterns:
--   (a) sync/pull steady-state delta:
--         WHERE user_id = ? AND updated_at >= (watermark - safeWindow) ORDER BY updated_at, id
--   (b) GET /supply-items cursor list (same keyset order).
--   (c) Cold-start drain keyset continuation:
--         row-value form (updated_at, id) > (cursor_updated_at, cursor_id) ORDER BY updated_at, id
--         Reuses this index, no additional index required (database-schema §4.2).
--
-- Note: INCLUDE (version) is intentionally omitted here for H2 compatibility in @DataJpaTest.
-- In production (PostgreSQL 15+) the index can be rebuilt with INCLUDE (version) for an
-- index-only de-dup check. The (id, version) de-dup that the client performs still works
-- with the version value from the SELECT projection.
create index ix_supply_items__sync_pull on supply_items (user_id, updated_at, id);
