-- mvp1 — auth core schema: identity + opaque rotating refresh tokens + email verification.
-- Columns used in any index/unique constraint are varchar (not text) so they index
-- cleanly on both PostgreSQL and H2 (PostgreSQL mode). Timestamps are tz-aware.

create table users (
    id             uuid primary key,
    email          varchar(320) not null unique,
    password_hash  varchar(200),
    locale         varchar(8),
    status         varchar(16)  not null default 'active',
    email_verified boolean      not null default false,
    created_at     timestamp with time zone not null,
    updated_at     timestamp with time zone not null,
    version        bigint       not null default 0,
    deleted_at     timestamp with time zone
);

create table refresh_token (
    id           uuid primary key,
    user_id      uuid         not null references users (id),
    token_hash   varchar(64)  not null unique,   -- SHA-256 hex of the opaque token; raw token never stored
    family_id    uuid         not null,
    device_id    varchar(128) not null,
    device_name  varchar(128),
    previous_id  uuid,
    expires_at   timestamp with time zone not null,
    revoked_at   timestamp with time zone,
    created_at   timestamp with time zone not null,
    last_seen_at timestamp with time zone not null
);
create index ix_refresh_token_family on refresh_token (family_id);
create index ix_refresh_token_user_device on refresh_token (user_id, device_id);

create table email_verification_token (
    id          uuid primary key,
    user_id     uuid        not null references users (id),
    token_hash  varchar(64) not null unique,     -- SHA-256 hex; raw token delivered by email only
    expires_at  timestamp with time zone not null,
    consumed_at timestamp with time zone,
    created_at  timestamp with time zone not null
);
