-- mvp1 — account_dek: per-account KMS-wrapped DEK store (field-encryption Scheme A, Task 0c).
-- ADR: docs/api-spec/adr/field-encryption-kms-dek.md Decision 1 (APPROVED, 2026-07-05).
-- Schema design: docs/api-spec/database-schema.md §6.9.
-- Plan Task 0c: docs/superpowers/plans/2026-07-05-field-encryption.md.
--
-- PURPOSE: stores one KMS-wrapped 256-bit DEK per account. The wrapped DEK is used
-- server-side to call KMS.Decrypt → deliver plaintext DEK to the device on login.
-- All health *_cipher columns are encrypted with the per-account DEK client-side.
--
-- SHRED INVARIANT (ADR IMPORTANT-2 — do not add a status column):
-- shred = HARD DELETE of this row only.
-- NO soft-status column exists by design: a soft flag would leave wrapped_dek intact →
-- KMS.Decrypt still succeeds → NOT a crypto-shred. This table has no 'status' column
-- and no soft-delete mechanism. The only lifecycle event is creation and hard DELETE.
--
-- FK ON DELETE RESTRICT: the DELETE must happen before the users row is deleted.
-- Deletion order enforced by:
--   (a) T0: AccountService.deleteAccount — immediately after setStatus("deleted") (sub-slice c)
--   (b) Tier-1 backstop: AccountErasureService.TIER1_CHILD_DELETE_ORDER first entry (sub-slice c)
-- Do NOT change to ON DELETE CASCADE — the shred must be an explicit, auditable step.
--
-- NOT a sync collection (N9): no client_id, no deleted_at, no tombstone.
-- created_at / updated_at / version are for ops and optimistic-concurrency only.
-- No trg_sync_stamp trigger (not a sync collection); updated_at is app-managed.

CREATE TABLE account_dek (
    -- PK = user_id: 1:1 with users. Natural idempotency key.
    -- Provisioning uses: INSERT INTO account_dek (...) ... ON CONFLICT (user_id) DO NOTHING
    -- so the loser of a concurrent insert race re-selects and uses the winner's DEK.
    user_id      uuid        NOT NULL,

    -- KMS CiphertextBlob: the opaque KMS-wrapped 256-bit DEK.
    -- NEVER the plaintext DEK. Never logged, never egressed, never in any API response.
    -- Updated on CMK rotation re-wrap (KmsClient.reEncryptDek → UPDATE SET wrapped_dek, kms_key_id).
    wrapped_dek  bytea       NOT NULL,

    -- CMK id/ARN used to wrap this DEK.
    -- Needed to route KMS.Decrypt and KMS.ReEncrypt correctly.
    -- Stable under automatic KMS key rotation (the ARN does not change; KMS selects the
    -- correct backing key material transparently). Updated only when switching to a new CMK.
    kms_key_id   text        NOT NULL,

    -- KMS EncryptionContext value bound at wrap time.
    -- Value: 'accountId=<canonical-lowercase-uuid>' (e.g. 'accountId=8f3a...-...-c1').
    -- NOT NULL: binds this wrapped blob to its account. KMS.Decrypt must present the
    -- identical context or the call fails — defense-in-depth above the field-level AAD.
    -- Also stored here as an audit trace of which account this DEK belongs to.
    wrap_context text        NOT NULL,

    -- DEK generation. 1 = current envelope version (AES-256-GCM, 0x01 prefix, AAD "v1:").
    -- Reserved for future true-DEK-change (version 0x02 / "v2:"), deferred (ADR Decision 6).
    -- Distinct from the 1-byte envelope version inside each *_cipher ciphertext blob.
    dek_version  smallint    NOT NULL DEFAULT 1,

    -- Ops / optimistic-concurrency block. NOT the sync-collection <sync> block.
    -- Access pattern: JPA entity (AccountDekRepository) — NOT raw-SQL-only. version is a
    -- Hibernate @Version optimistic-lock column. DEFAULT 0 matches the codebase convention:
    -- Hibernate starts @Version at 0 on INSERT (see V20260629000001__mvp1_auth_core.sql:14,
    -- V20260629000006__mvp1_supply_items.sql:77). Do NOT map this column as @Version with
    -- DEFAULT 1 — it would cause a silent off-by-one (first UPDATE expects version=1 but row
    -- holds version=0, and Hibernate would throw OptimisticLockException on the first write).
    -- updated_at is set by the application layer (no trg_sync_stamp trigger here).
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0,

    CONSTRAINT pk_account_dek
        PRIMARY KEY (user_id),

    -- FK ON DELETE RESTRICT: enforces that account_dek must be deleted before users.
    -- Explicit ordered delete is required (see TIER1_CHILD_DELETE_ORDER in sub-slice c).
    CONSTRAINT fk_account_dek__users
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,

    CONSTRAINT ck_account_dek__dek_version
        CHECK (dek_version >= 1)
);

-- No additional index beyond the PK.
-- The only lookup pattern is user_id (login-delivery SELECT, provisioning INSERT,
-- T0 shred DELETE, CMK rotation UPDATE). The PK btree covers all four patterns exactly.
-- account_dek is not a sync collection → no ix_account_dek__sync_pull index.

-- Grants (database-schema.md §6.4 / §6.9 grant matrix):
--
-- mom_app_rw (Spring Boot runtime — online path):
--   SELECT : login-delivery — read wrapped_dek + kms_key_id to call KMS.Decrypt
--   INSERT : provisioning — write wrapped_dek on account create (ON CONFLICT DO NOTHING)
--   UPDATE : CMK rotation re-wrap — KmsClient.reEncryptDek updates wrapped_dek + kms_key_id
--   DELETE : T0 crypto-shred — AccountService.deleteAccount immediately after setStatus("deleted")
--
-- mom_gc (scheduled erasure job — belt-and-suspenders only):
--   DELETE : idempotent Tier-1 FK backstop in AccountErasureService.TIER1_CHILD_DELETE_ORDER
--            (T0 has already deleted the row on the normal path; this prevents a surviving
--            row from FK-violating the Tier-2 DELETE FROM users)
--
-- mom_readonly : NO grant — wrapped_dek must not be readable by reporting/monitoring roles.
-- mom_owner   : implicit as DDL owner; used only for migration, not the running app.
--
-- NOTE: GRANT statements are intentionally omitted from this Flyway migration.
-- These roles are managed outside Flyway (production-only DDL run by the DBA) because:
--   1. H2 (test environment) does not define mom_app_rw / mom_gc / mom_readonly roles.
--   2. No other migration in this project includes GRANTs (consistent with project convention).
-- Production apply order: create roles (Terraform/DBA) → run migrations → run grants.
-- Reference commands (run manually in prod, NOT via Flyway):
--   GRANT SELECT, INSERT, UPDATE, DELETE ON account_dek TO mom_app_rw;
--   GRANT DELETE                         ON account_dek TO mom_gc;
