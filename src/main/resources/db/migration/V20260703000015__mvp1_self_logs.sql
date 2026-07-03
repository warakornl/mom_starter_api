-- mvp1 — self_log: self-reported health metrics (SD-5 health data, database-schema §1.5 / ADR self-log-encryption-posture.md).
-- Capture type (b): generic self-log — IMMUTABLE EVENT (create-on-push, never client-updated after push).
-- MOTHER-health collection.  Gated by cloud_storage (whole-batch) + email_verified (§4 / contract).
-- No new consent type.  Read gate: auth + email_verified ONLY (ADR Decision 5).
--
-- Five metric types: weight · blood_pressure · swelling · lochia · symptom.
--
-- id is CLIENT-GENERATED (uuid v4) — NOT a DB DEFAULT (sync spec §A.4 / data-model §2).
--   Re-push with the same id at the same version is an idempotent no-op (sync §A.8).
--
-- logged_at is FLOATING-CIVIL (timestamp WITHOUT TIME ZONE — FLAG-1 / database-schema §0.2).
--   The calendar BUCKET KEY for date-range filtering and history ordering.
--   NEVER UTC-normalized; DATE part is the bucket; consistent with kick_count_session.started_at
--   and expense.incurred_on (FLAG-1 carry-forward, data-model §5 / database-schema §0.2).
--   Corrects the Slice-1 plan's `text` type; database-schema §1.5 `timestamp` governs (ADR Decision 7).
--
-- ENCRYPTION POSTURE — Option A (ADR Decision 1 / database-schema §1.5 / pdpa-assessment ruling 2.1):
--   value_numeric / value_numeric_secondary / value_text / note_cipher are `bytea` ciphertext columns.
--   MVP POSTURE: hold PLAINTEXT BYTES today (no-op / passthrough cipher — KMS + AES-GCM EAS build
--   is BLOCKED in this environment, HANDOFF §3).  Real AES-GCM ciphertext lands in THE SAME COLUMNS
--   at the KMS/EAS milestone with ZERO schema change and ZERO contract change.
--   This is EXACTLY the kick_count_session.note_cipher pattern already merged and green (545 tests).
--   At-rest protection for MVP relies on AWS RDS volume/disk encryption, IAM least-privilege, TLS
--   in transit — NOT per-field column encryption.  PDPA residual risk: acknowledged in ADR §PDPA;
--   security-compliance + owner sign-off required before production launch (ADR §PDPA / ruling 2.1).
--
--   Server NEVER parses / queries / aggregates any bytea value column (G4 / INV-S2).
--   Crypto-shred on soft-delete: value_numeric, value_numeric_secondary, value_text, note_cipher
--   are all SET NULL in the same tombstone UPDATE (§4.4(A) / PDPA ruling 5a).
--   Per-account DEK shred on DELETE /account erases them at the crypto layer (ruling 5b).
--
-- FK reconciliation (ADR Decision 7 note 1):
--   REFERENCES users(id) — physical table is `users`, NOT `app_user`.
--   No ON DELETE clause → RESTRICT (default).  Erasure is application-level:
--   Tier-1 explicit child purge (ม.33) + tombstone GC (180 days), identical to
--   kick_count_session / expense / every other health collection.
--
-- version DEFAULT (ADR Decision 7 note 3):
--   database-schema §1.5 sketches DEFAULT 1; implemented sync engine (kick_count_session, expense)
--   uses DEFAULT 0 as a pre-stamp safety net with JPA @Version stamping 1 on first apply.
--   Using DEFAULT 0 for engine parity — observable version on any applied row is >= 1 either way.
--
-- RETENTION (database-schema §4.4):
--   Tombstone GC = TOMBSTONE_TTL = 180 days (offline-sync §A.5).
--   Account deletion → Tier-1 purge of all self_log rows (ม.33) + per-account DEK shred.
--
-- Reversibility:
--   Forward-only (new table).  Rollback = DROP TABLE self_log.

CREATE TABLE self_log (

    -- PK: CLIENT-GENERATED uuid v4 (offline-safe, idempotent push — sync spec §A.4 / data-model §2).
    -- No GENERATED / DEFAULT: the server MUST NOT mint this id.
    id                       uuid                        PRIMARY KEY,

    -- Owner.  NOT NULL; every log entry belongs to exactly one user.
    -- REFERENCES users(id) — physical table name; app_user was never created (ADR Decision 7 note 1).
    -- No ON DELETE clause → RESTRICT (application-level erasure: Tier-1 purge on ม.33).
    user_id                  uuid                        NOT NULL REFERENCES users (id),

    -- metric_type: plaintext enum (the query/filter key — ADR Decision 2).
    -- CHECK backstops the apply-path enum guard.  text + CHECK for reversible Flyway migrations
    -- (database-schema §0.2 — same pattern as every other enum in this schema).
    -- Server NEVER infers health state from this value (G4 / INV-S2); it is only a routing label.
    metric_type              text                        NOT NULL
        CHECK (metric_type IN ('weight','blood_pressure','swelling','lochia','symptom')),

    -- VALUE columns — bytea ciphertext (SD-5 health values).
    -- MVP: hold PLAINTEXT bytes (no-op passthrough cipher; see ENCRYPTION POSTURE above).
    -- Same columns hold real AES-GCM ciphertext at KMS milestone — zero schema change.
    -- Server NEVER parses, compares, or aggregates these (G4 / INV-S2 structural guarantee:
    --   no SQL predicate over a bytea value is even syntactically coherent for numeric ranges).
    -- Nullable: (a) only the relevant subset populates per metric_type (client routing);
    --           (b) crypto-shredded to NULL on tombstone (§4.4(A) / PDPA ruling 5a).
    --
    -- value_numeric: primary numeric value (weight kg / BP systolic mmHg).
    value_numeric            bytea,
    -- value_numeric_secondary: secondary numeric value (BP diastolic mmHg; NULL for non-BP metrics).
    value_numeric_secondary  bytea,
    -- value_text: descriptive text value (swelling level / lochia description / symptom free-text).
    --   PDF gate: included under sensitive_lab_results opt-in (ADR Decision 6 / contract line 630).
    value_text               bytea,
    -- note_cipher: optional free-text note (any metric).  PDF gate: sensitive_lab_results.
    --   Mirrors kick_count_session.note_cipher pattern exactly.
    note_cipher              bytea,

    -- unit: non-sensitive plaintext display label chosen by metric_type.
    -- Examples: 'kg' (weight), 'mmHg' (blood_pressure), NULL (swelling/lochia/symptom).
    -- Plaintext so the client can render the unit without decryption.  Never keyed on.
    unit                     text,

    -- logged_at: FLOATING-CIVIL bucket key (FLAG-1 — database-schema §0.2 / §1.5).
    -- DB type: timestamp WITHOUT TIME ZONE — never UTC-normalized.
    -- The DATE part is the calendar bucket for GET /self-logs?from=&to= range filtering.
    -- Used for ordering within a civil day; cross-device merge ordering uses updated_at ONLY.
    -- Corrects the Slice-1 plan's `text` type; §1.5 `timestamp` governs (ADR Decision 7 note 2).
    logged_at                timestamp without time zone NOT NULL,

    -- <sync> block (data-model §1.2 / §2 / database-schema §0.3).
    --   created_at  : server-assigned on first INSERT; never changed after.
    --   updated_at  : server-assigned on EVERY apply; the single LWW merge clock.
    --                 Immutable-event: bumped only on tombstone (re-push of same id at same
    --                 version is an idempotent no-op — sync §A.8).
    --   version     : server-assigned monotonic optimistic-concurrency token (@Version Long).
    --                 DEFAULT 0 is a pre-stamp safety net; the apply path always stamps explicitly.
    --                 Observable version on any real applied row is >= 1 (ADR Decision 7 note 3).
    --   deleted_at  : soft-delete tombstone.  NULL = live.  NOT NULL = tombstone (tombstone-wins,
    --                 offline-sync §A.5).  GC after TOMBSTONE_TTL (180 days, database-schema §4.4).
    --                 On tombstone: crypto-shred value_numeric/secondary/text/note_cipher (§4.4(A)).
    --   client_id   : originating device uuid; LWW tie-break ONLY (sync spec §A.6).
    --                 No FK — multi-device; device rows are not managed here.
    created_at               timestamp with time zone    NOT NULL DEFAULT now(),
    updated_at               timestamp with time zone    NOT NULL DEFAULT now(),
    version                  bigint                      NOT NULL DEFAULT 0,
    deleted_at               timestamp with time zone,
    client_id                uuid

);

-- (a) Sync-pull keyset / steady-state delta + (b) GET /self-logs cursor when no metricType filter.
-- Covers:
--   (a) sync/pull:   WHERE user_id = ? AND updated_at >= (watermark - safeWindow) ORDER BY updated_at, id
--   (b) GET list:    WHERE user_id = ? ORDER BY updated_at, id  (keyset cursor)
--   (c) Cold-start:  AND (updated_at > cursorUpdatedAt OR (updated_at = cursorUpdatedAt AND id > cursorId))
-- INCLUDE (version) intentionally omitted for H2 compatibility (same as every other sync-pull index).
-- ADR Decision 1 / database-schema §4.2 / offline-sync §B.4.
CREATE INDEX ix_self_log__sync_pull        ON self_log (user_id, updated_at, id);

-- GET /self-logs history/range (no metricType filter) — ORDER BY logged_at DESC, id DESC keyset.
-- Covers: GET /self-logs?from=&to= range query keyed on logged_at (floating-civil bucket key).
-- The DATE part of logged_at is the calendar bucket; from/to are civil-date range predicates.
-- Residual filter on deleted_at (live rows only) is a post-filter — self_log cardinality per user
-- is moderate (multiple entries/day per metric but still bounded) and a partial index would reduce
-- H2 portability without measurable gain at MVP scale (same rationale as kick_count_session §history).
-- ADR Decision 1 / ADR Decision 2.
CREATE INDEX ix_self_log__user_time        ON self_log (user_id, logged_at, id);

-- GET /self-logs with metricType filter — ORDER BY logged_at DESC, id DESC keyset.
-- Covers: GET /self-logs?metricType=weight (or any metric) range+filter query.
-- metric_type is the leading column so the index is selective per metric (ADR Decision 2 / spec §A.2).
-- Subsumes ix_self_log__user_time for the filtered case; both are kept because the unfiltered
-- cursor (above) uses (user_id, logged_at, id) without the metric_type prefix.
-- ADR Decision 1 / ADR Decision 2.
CREATE INDEX ix_self_log__user_metric_time ON self_log (user_id, metric_type, logged_at, id);

-- Tombstone GC sweep: DELETE FROM self_log WHERE deleted_at IS NOT NULL AND deleted_at < ?
-- Global (non-user-scoped) sweep — ix_self_log__sync_pull cannot serve it (leads with user_id).
-- Plain index for H2 portability; PROD FOLLOW-UP: partial WHERE deleted_at IS NOT NULL (CONCURRENTLY).
CREATE INDEX ix_self_log__deleted_at ON self_log (deleted_at);
