-- mvp1 — pregnancy_profile name ciphers: additive nullable bytea columns for mother/baby
-- identity PII (PDPA s.26 sensitive personal data — Option A encrypted-bytea).
-- ADR: docs/api-spec/name-fields-design.md Decision 1 (placement) + Decision 2 (Option A) +
--       Decision 5 (PDPA pipeline) + Decision 6 (ER diagram sync) — APPROVED 2026-07-07.
-- Parent entity: V20260629000004__mvp1_pregnancy_profile.sql.
--
-- PURPOSE: stores three optional identity-PII name fields (mother first name, mother last name,
-- baby name) on the existing 1:1 pregnancy_profile row.  All three are NULLABLE per owner
-- decision (OQ-N-OWN2/OQ-N-OWN3 RESOLVED 2026-07-07).
--
-- ENCRYPTION POSTURE — Option A (ADR Decision 2 / name-fields-design.md §2):
--   All three columns hold `bytea` under the same no-op MVP / AES-256-GCM seam as the
--   sibling health ciphers (kick_count_session.note_cipher,
--   self_log.value_numeric/secondary/text/note_cipher, medication_plan.name_cipher, etc.).
--   MVP POSTURE: hold PLAINTEXT BYTES (no-op / passthrough cipher — KMS + AES-GCM build is
--   deferred).  Real AES-256-GCM lands in THESE SAME COLUMNS at the KMS/EAS milestone with
--   ZERO schema change and ZERO contract change (kick_count_session.note_cipher precedent).
--   At-rest protection for MVP relies on AWS RDS volume/disk encryption, IAM least-privilege,
--   and TLS in transit — NOT per-field encryption.
--   PDPA residual risk: acknowledged by owner (OQ-N-OWN1 RESOLVED, name-fields-design.md §2).
--   Security-compliance + formal PDPA classification (OQ-N-SEC1 = Option A; ruling 2.1
--   formal annotation in flight) must be recorded before production launch.
--
-- SERVER NEVER PARSES / QUERIES these cipher columns.
--   - no LENGTH/charset validation server-side (client enforces ≤100 char cap)
--   - no ORDER BY, WHERE, or aggregate over any *_cipher name column
--   - server ciphertext byte-cap enforcement → name_too_large sub-code (name-fields-design §4)
--   - NO BLOOM / bidx index (contrast email_bidx which enables server-side login lookup)
--
-- CRYPTO-SHRED on profile tombstone (PDPA ม.33 / name-fields-design §5c / ADR §4.4(A)):
--   These three columns MUST be SET to NULL in the same UPDATE that writes deleted_at on the
--   profile row (per-row cipher-NULL shred, §4.4(A)).  The shred is surfaced as a repository
--   method: PregnancyProfileRepository.shredCiphersByUserId(userId).
--
--   springboot-backend-dev MUST CALL shredCiphersByUserId(userId) inside the pregnancy-profile
--   soft-delete / tombstone UPDATE path (whichever code sets deleted_at on the profile row).
--   This is analogous to MedicationPlanSyncCollection.applyDelete() which calls
--   plan.setNameCipher(null) before setting deleted_at.
--
--   The DEK-based T0 crypto-shred (AccountService.deleteAccount → accountDekRepository
--   .deleteByUserId) destroys all *_cipher bytes indirectly (KMS.Decrypt becomes impossible).
--   The per-row NULL shred is the belt-and-suspenders PDPA T0 evidence that survives on disk.
--
-- TIER-1 HARD-PURGE: pregnancy_profile is already listed in
--   AccountErasureService.TIER1_CHILD_DELETE_ORDER and TombstoneGcService.PURGE_TABLES.
--   The whole row is hard-deleted at 180d; the three name cipher columns are carried along
--   automatically with the row — no separate purge entry is needed.
--
-- ADDITIVE / REVERSIBLE: nullable ADD COLUMN only — zero existing data touched.
-- No new constraints, no new triggers, no new indexes (see SERVER NEVER PARSES above).
-- DOWN (if ever needed): ALTER TABLE pregnancy_profile DROP COLUMN <col> (additive-only;
-- dropping is safe and data-loss is INTENTIONAL in an erasure context).
--
-- NO GRANT statements — roles (mom_app_rw / mom_gc / mom_readonly / mom_owner) are managed
-- outside Flyway (H2 test environment has no role DDL; consistent with all prior migrations).

ALTER TABLE pregnancy_profile
    ADD COLUMN mother_first_name_cipher bytea NULL;

ALTER TABLE pregnancy_profile
    ADD COLUMN mother_last_name_cipher bytea NULL;

ALTER TABLE pregnancy_profile
    ADD COLUMN baby_name_cipher bytea NULL;

-- No index on any of the three columns.
-- Rationale: these cipher columns are NEVER queried server-side — no WHERE, no ORDER BY,
-- no aggregate.  The only server access pattern is:
--   (a) SELECT * FROM pregnancy_profile WHERE user_id = ?  (profile GET — full row fetch; no
--       separate column-level index path needed beyond the existing UNIQUE(user_id) constraint).
--   (b) UPDATE ... SET *_cipher = NULL WHERE user_id = ?  (per-row shred — also covered by
--       the UNIQUE(user_id) constraint / heap scan on the single-row result).
-- Contrast email_bidx (bloom index over email_cipher): email is searched server-side for login
-- uniqueness checks.  Name ciphers are identity PII stored by the client, read back by the
-- client, never searched by the server.
