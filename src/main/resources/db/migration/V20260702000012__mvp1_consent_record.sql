-- mvp1 — consent_record: append-only PDPA legal record of per-user, per-purpose consents.
-- Supports PDPA ม.19 (explicit consent + withdrawal) and ม.26 (health data audit proof).
--
-- DESIGN DECISIONS (database-engineer, 2026-07-02 — see consent-slice-design.md):
--
-- [1] APPEND-ONLY: no UPDATE, no version/If-Match. Each grant OR withdrawal is a new
--     immutable row. "Current" consent = latest granted_at per (user_id, consent_type).
--     Rationale: PDPA audit trail (ม.26 — controller must prove consent obtained/withdrawn);
--     withdrawal = INSERT granted=false (fail-closed immediately, ม.19(ค)); no LWW race.
--
-- [2] FK to `users` (not `app_user`): the physical table created by
--     V20260629000001__mvp1_auth_core.sql is named `users`. The database-schema.md §1.3
--     sketch referenced `app_user` — this migration uses the real name.
--
-- [3] consent_type CHECK: 6 types per contract + child-health-consent.md ruling.
--     Uses VARCHAR(32) + CHECK (not a PostgreSQL ENUM TYPE) so adding a 7th type is an
--     additive ALTER TABLE ... ADD CONSTRAINT (no column rewrite, no type drop required).
--
-- [4] NO deleted_at for client tombstoning: consent_record is NOT a sync/push collection.
--     Client "deletion" = INSERT granted=false (withdrawal). Physical row purge is done
--     only by the legal-hold GC after ~1 yr post-erasure (database-schema.md §4.4(B)).
--
-- [5] granted_at: server-assigned (DEFAULT now()). Client clock is untrusted.
--     created_at: same semantics, kept for schema consistency with other tables.
--
-- [6] locale CHECK ('th'|'en'): records which language the consent text was shown in.
--     Required for PDPA ม.19(ข) audit ("plain language" condition).
--
-- [7] No jsonb: all columns are plain SQL types — no h2-masks-jsonb-binding risk.
--     H2 (PostgreSQL-mode) runs this migration identically to real PostgreSQL.
--
-- RETENTION (database-schema.md §4.4(B)):
--   - NOT subject to 180-day tombstone GC (no deleted_at, not a synced health collection).
--   - Survives account erasure (DELETE /account) for the legal-hold window (~1 yr).
--   - CRITICAL: the erasure cascade at DELETE /account must SOFT-DELETE + DEK-crypto-shred
--     the `users` row (stamp deleted_at, destroy DEK) — it must NOT hard-delete `users`.
--     consent_record holds NO ciphertext so crypto-shred does not touch it.
--     The physical `users` row remains, keeping this FK RESTRICT valid, until the
--     legal-hold GC runs ~1 yr post-erasure and hard-purges consent_record + users together.
--   - Policy: retain consent_record ~1 year after account erasure, then purge.
--   - 🚩 "~1 year" is a policy proposal; Thai legal counsel must confirm before launch.
--   - Cloud-infra-engineer owns the legal-hold GC scheduling (handoff per database-schema.md §9).
--
-- SECURITY:
--   - No sensitive health values stored here (only metadata: type, granted, version, locale).
--   - Covered by RDS KMS volume encryption (no column-level encryption needed).
--   - Least-privilege: app DB user (mom_app role) needs INSERT + SELECT only; no UPDATE/DELETE.
--
-- REVERSIBILITY:
--   Forward-only (new table with no existing dependencies at migration time).
--   Rollback: DROP TABLE consent_record;
--   WARNING: In a live system with consent rows, rolling back destroys legal evidence.
--   Roll forward instead — drop and recreate with corrected DDL if needed.
--
-- H2 COMPATIBILITY:
--   All column types are H2-compatible in PostgreSQL mode.
--   No jsonb, no custom ENUM TYPE, no GENERATED columns.
--   One CREATE TABLE + one CREATE INDEX = clean in both H2 and PostgreSQL.

CREATE TABLE consent_record (
    id                   uuid         PRIMARY KEY,

    -- Every consent belongs to exactly one user.
    -- References the physical `users` table (V20260629000001__mvp1_auth_core.sql).
    -- ON DELETE RESTRICT (default) is valid here because the `users` row is NEVER
    -- hard-deleted during the erasure cascade.  At DELETE /account:
    --   (a) users row is SOFT-DELETED (deleted_at stamped) + DEK crypto-shredded
    --   (b) child health-data rows are hard-purged immediately
    -- The physical `users` row is RETAINED so that consent_record (and report_audit)
    -- can continue to reference it.  The legal-hold GC (~1 yr post-erasure) then
    -- hard-purges `users` + `consent_record` + `report_audit` TOGETHER in dependency
    -- order (consent_record first, then users).
    -- consent_record holds NO ciphertext, so DEK crypto-shred does not touch it.
    -- Aligned with pdpa-assessment.md ruling 5b (crypto-shred approach) and
    -- database-schema.md §6.5 (legal-hold GC sequencing).
    user_id              uuid         NOT NULL REFERENCES users (id),

    -- Which PDPA consent purpose this record addresses.
    -- VARCHAR(32) + CHECK: varchar (not text) so H2 PostgreSQL-mode indexes cleanly
    -- (project convention per h2-masks-jsonb-binding memory; text is unindexable on H2).
    -- Additive extension: adding a 7th type = ALTER ... ADD CONSTRAINT, no column rewrite.
    -- Values must match the consentType enum in the API contract and ConsentRecordInput.
    consent_type         varchar(32)  NOT NULL
        CHECK (consent_type IN (
            'general_health',        -- ม.26: health data processing (mother) — S3 first-run
            'sensitive_lab_results', -- ม.26: decrypt note columns into PDF — JIT at PDF
            'pdf_egress',            -- ม.26: produce a PDF at all — JIT first PDF
            'infant_feeding',        -- ม.20: parental basis, feeding data — JIT first log
                                     --   dual-gated with general_health (both required)
            'cloud_storage',         -- ม.26: sync/push + sync/pull egress — S3 first-run
            'child_health'           -- ม.20 (parental) + ม.26 (sensitive health) +
                                     --   ม.21/27 (purpose limitation) — JIT first save
        )),

    -- true = consent granted; false = consent withdrawn.
    -- Withdrawal = INSERT with granted=false (not UPDATE). Append-only.
    granted              boolean      NOT NULL,

    -- Version tag of the consent text (privacy policy copy) that was shown to the user.
    -- Required to prove that consent was given for the correct, current text (ม.19(ข)).
    -- Client sends this; server stores verbatim (e.g. "v1.0-th", "v2.1-en").
    consent_text_version text         NOT NULL,

    -- Language code of the consent text shown (ม.19(ข): must be plain, understandable language).
    -- 'th' = Thai, 'en' = English.
    -- VARCHAR(8) matches users.locale varchar(8); varchar (not text) for H2 index compatibility.
    -- Server normalises Accept-Language header -> 'th'|'en' BEFORE insert (see design §3.1);
    -- the CHECK here is the last-line-of-defence guard, not the primary normalisation.
    locale               varchar(8)   NOT NULL
        CHECK (locale IN ('th', 'en')),

    -- Server-authoritative timestamp of this grant/withdrawal action.
    -- DEFAULT now() ensures the server clock is used even if the client omits it.
    -- This is the ordering column for "current consent" lookup (see index below).
    granted_at           timestamptz  NOT NULL DEFAULT now(),

    -- Row-creation instant (same value as granted_at in practice; kept for schema consistency).
    created_at           timestamptz  NOT NULL DEFAULT now()

    -- NO deleted_at: consent_record is never client-tombstoned.
    -- Withdrawal = new row with granted=false. Physical purge = legal-hold GC only.
    -- NO version / If-Match: append-only records take no optimistic-concurrency token
    --   (contract: "account/consents is append-only, so it takes no If-Match").
    -- NO updated_at: immutable rows never change after insert.
);

-- HOT PATH INDEX: serves ConsentChecker.isGranted(userId, consentType).
--
-- Query pattern (deterministic tiebreak — withdrawal wins ties, fail-safe):
--   SELECT granted FROM consent_record
--   WHERE user_id = ? AND consent_type = ?
--   ORDER BY granted_at DESC, granted ASC, id DESC
--   LIMIT 1;
--
-- The composite (user_id, consent_type, granted_at DESC, id DESC) index covers the
-- WHERE prefix exactly and provides the primary ORDER BY columns in a single index scan.
-- PostgreSQL returns the first matching row from the index without a sort step for the
-- common case — O(log n) on user cardinality.
-- `granted ASC` (withdrawal wins ties) is a secondary sort applied on the rare
-- concurrent-same-timestamp subset; it does not require a separate sort step in practice.
--
-- Also serves GET /account/consents history list per user (user_id prefix scan).
CREATE INDEX ix_consent_record__user_type_time
    ON consent_record (user_id, consent_type, granted_at DESC, id DESC);
