-- mvp1 — password-reset tokens (single-use, short-expiry; raw token emailed, stored only as SHA-256).
create table password_reset_token (
    id          uuid primary key,
    user_id     uuid        not null references users (id),
    token_hash  varchar(64) not null unique,
    expires_at  timestamp with time zone not null,
    consumed_at timestamp with time zone,
    created_at  timestamp with time zone not null
);
