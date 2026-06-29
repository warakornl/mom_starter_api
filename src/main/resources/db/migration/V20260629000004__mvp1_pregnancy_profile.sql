-- mvp1 — PregnancyProfile: drives the whole calendar/stage for the pregnancy companion app.
-- One profile per user (UNIQUE user_id). Pull-replicated to all user devices via sync/pull;
-- written only via PUT /pregnancy-profile (direct-REST, NOT sync/push). Data-model §3.1.
--
-- PDPA note: `edd` (estimated due date) is SENSITIVE HEALTH DATA (PDPA s.26).
-- At-rest encryption is controlled at the RDS/KMS level in production (infra-diagram); no
-- column-level or application-layer encryption is added here. Any field-level encryption
-- decision requires a dedicated security-compliance review before implementation.
--
-- Derived fields (gestational_week, current_stage, days_remaining, progress,
-- delivery_window_active) are NEVER stored in the DB — they are computed at runtime from `edd`
-- using the canonical algorithm (data-model §3.1 "Canonical gestational-age & stage computation
-- (PINNED)"). Storing them would create a stale-data risk and violate data-model principle §4.

create table pregnancy_profile (
    id             uuid                     primary key,

    -- 1 profile per user (data-model §3.1: "||--o|" cardinality). UNIQUE also acts as the
    -- lookup index for GET /pregnancy-profile — no separate index needed for user_id.
    user_id        uuid                     not null references users (id),

    -- EDD is the single stored civil date anchor (zoneless, proleptic Gregorian — FLAG-1).
    -- DB type `date` (no time, no timezone) matches the civil-date rule exactly.
    -- Health data: see PDPA note above.
    edd            date                     not null,

    -- How EDD was derived at input time: entered directly ('due_date') or back-computed
    -- from a currentWeek input ('current_week') — data-model OQ-7.
    edd_basis      varchar(16)              not null
                       check (edd_basis in ('due_date', 'current_week')),

    -- Lifecycle state (data-model §3.1). MVP ships 'pregnant' only.
    -- 'postpartum' and 'ended' are written by POST /pregnancy-profile/birth-event
    -- (deferred phase — data-model "Deferred to birth-event phase"). Columns for birth
    -- data are present now to avoid an additive migration at that phase.
    lifecycle      varchar(16)              not null default 'pregnant'
                       check (lifecycle in ('pregnant', 'postpartum', 'ended')),

    -- Set by the deferred birth-event phase. Absolute UTC instant (timestamptz — FLAG-1).
    birth_datetime timestamp with time zone,

    -- Free-value delivery type (e.g. 'vaginal', 'cesarean'). Nullable; set at birth event.
    delivery_type  varchar(64),

    -- Free-text birth note. Language-agnostic; stored verbatim; never parsed.
    birth_note     text,

    -- Optimistic-concurrency token (data-model §2 <sync>; api-contract B2).
    -- Server-assigned, monotonic, starts at 0. Sent as If-Match value on PUT;
    -- mismatch → 409 Conflict; header absent → 428 Precondition Required.
    version        bigint                   not null default 0,

    -- System/server timestamps (absolute UTC, timestamptz — FLAG-1 & data-model §2 <sync>).
    -- updated_at is the LWW clock for pull-replication ordering (api-contract sync semantics).
    created_at     timestamp with time zone not null default now(),
    updated_at     timestamp with time zone not null default now(),

    -- Soft-delete tombstone. Null = live record; non-null = deleted. Propagated via sync/pull
    -- so deleted profiles disappear on all devices (data-model §2 <sync>).
    deleted_at     timestamp with time zone,

    unique (user_id)
);

-- Pull-replication keyset index for sync/pull change-set pagination (api-contract:
-- "keysets within each collection by (updated_at, id) — ix_<t>__sync_pull composite").
-- Required even though PregnancyProfile is not push-accepted: the server includes it in
-- the pull change-set so other devices receive updates (data-model §3.1 B1).
create index ix_pregnancy_profile__pull on pregnancy_profile (updated_at, id);
