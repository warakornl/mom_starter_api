-- mvp1 — federated identity links (e.g. Google). One row per (provider, provider_sub).
-- Running number 000003: 000002 is taken by the password-reset slice on a sibling branch
-- (manual running-number coordination to avoid a Flyway collision at merge).
create table auth_identity (
    id           uuid primary key,
    user_id      uuid         not null references users (id),
    provider     varchar(32)  not null,
    provider_sub varchar(255) not null,
    email        varchar(320),
    created_at   timestamp with time zone not null,
    unique (provider, provider_sub)
);
