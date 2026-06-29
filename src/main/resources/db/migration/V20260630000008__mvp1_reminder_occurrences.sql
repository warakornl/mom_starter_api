-- mvp1 — reminder_occurrences: fired-instance history & status (data-model §3.5, US-2).
-- MOTHER-health collection.  Gated by general_health (per-collection) + cloud_storage (whole-batch).
-- Part of the calendar/reminder slice (api-contract §"Calendar · appointment · reminder-occurrence pins").
--
-- KEY DESIGN DECISIONS (all PINNED in api-contract FLAG-7 / OQ-CAL-4/6):
--
-- (1) W-A SPARSE TABLE (OQ-CAL-4 — PINNED).
--     "due" instances are PROJECTED locally from the Reminder definition via FLAG-4 expansion.
--     A ReminderOccurrence row is created + pushed ONLY on a terminal user action: done or snoozed.
--     "missed" is derived on-device (end-of-local-day) and NOT pushed in MVP.
--     → This table holds ONLY terminal done/snoozed rows (no row-per-due, no missed rows).
--     The status CHECK keeps all four values for local-store + read-only GET paths.
--
-- (2) DETERMINISTIC id (N6/N7 — PINNED).
--     id = uuidv5(OCCURRENCE_NAMESPACE, reminderId + "|" + scheduledLocalCivil)
--     OCCURRENCE_NAMESPACE = 4328078f-6339-4c38-a2ce-eabff6cbf387 (frozen, byte-identical on
--     iOS/Android/server — OccurrenceId.NAMESPACE in the codebase).
--     The server RECOMPUTES the id on sync/push and rejects a mismatch (422 validation_error —
--     so a buggy client cannot fork one occurrence into two rows).
--     id is CLIENT-GEN (same uuidv5 algorithm) — no DB DEFAULT.
--
-- (3) NATURAL-KEY UNIQUENESS (N6/N7 / data-model §5 carry-forward).
--     uq_reminder_occurrences__natural (user_id, reminder_id, scheduled_local_time) is a DB
--     backstop: even if the server-side id-recompute were bypassed, a client cannot insert two
--     rows for the same (reminder, civil-time) pair.
--
-- (4) MINUTE-PRECISION CHECK (api-contract §"Recurrence grammar" §(c) / ck_reminder_occurrence__minute).
--     The uuidv5 name is "YYYY-MM-DDTHH:mm" — no seconds.
--     scheduled_local_time must have SECOND = 0 (enforced here; also validated 422 on push).
--
-- (5) reminder_id is a SOFT LINK (NO FK) — OQ-CAL-6 orphan tolerance.
--     Tombstoning the parent Reminder does NOT cascade to its occurrence rows.
--     Past done/snoozed rows are retained as adherence history; the calendar/PDF renders them
--     from the occurrence's own scheduled_local_time even when the Reminder is tombstoned.
--     reminder_id is NOT NULL: every occurrence is generated from a specific Reminder definition.
--
-- (6) M1 STATUS-MERGE PRECEDENCE (US-15 AC#4 / multi-device sync §"Reminders across devices").
--     Explicit done/snoozed outranks derived missed for the same id, regardless of timestamp.
--     Enforced in the sync apply path (StatusMerge.java), not in DB constraints.
--
-- Reversibility: Forward-only (new table). Rollback = DROP TABLE reminder_occurrences.

CREATE TABLE reminder_occurrences (

    -- PK: deterministic uuidv5 — CLIENT-COMPUTED.  No GENERATED / DEFAULT.
    -- Computed as: uuidv5(OCCURRENCE_NAMESPACE, reminderId + "|" + scheduledLocalCivil)
    id                      uuid                        PRIMARY KEY,

    -- Owner.  NOT NULL.
    user_id                 uuid                        NOT NULL REFERENCES users(id),

    -- reminder_id: soft link to the parent Reminder (NO FK — OQ-CAL-6 orphan tolerance).
    -- NOT NULL: every occurrence is generated from a specific Reminder definition.
    -- A row whose reminder_id references a tombstoned Reminder is valid and retained.
    reminder_id             uuid                        NOT NULL,

    -- scheduled_local_time: the floating civil wall-clock instant of this occurrence (FLAG-1).
    -- DB type: timestamp WITHOUT TIME ZONE — never UTC-normalized (civil wall-clock).
    -- Format (app-enforced): YYYY-MM-DDTHH:mm at MINUTE precision (no seconds, no zone).
    -- This exact string is fed to uuidv5() for the deterministic id derivation; must match
    -- the id recomputed by the server on sync/push, else 422.
    scheduled_local_time    timestamp without time zone NOT NULL,

    -- status: lifecycle state of this occurrence instance.
    -- due    : projected/default — a stored row in this status is valid only in the local store.
    -- done   : terminal user action (pushed via sync/push).
    -- snoozed: terminal user action (pushed; snoozed_until is set).
    -- missed : derived on-device end-of-local-day — NOT pushed in MVP (W-A).
    -- M1 merge rule: done/snoozed always outranks missed for the same id (StatusMerge.java).
    status                  text                        NOT NULL
                                CHECK (status IN ('due', 'done', 'snoozed', 'missed')),

    -- acted_at: absolute-UTC instant of the done/snoozed action.  Null for due/missed.
    -- Used as the LWW clock on the status field (together with server updated_at).
    acted_at                timestamp with time zone,

    -- snoozed_until: absolute-UTC re-fire instant.  Null unless status = snoozed.
    snoozed_until           timestamp with time zone,

    -- <sync> block (data-model §1.2 / §2).
    created_at              timestamp with time zone    NOT NULL DEFAULT now(),
    updated_at              timestamp with time zone    NOT NULL DEFAULT now(),
    version                 bigint                      NOT NULL DEFAULT 0,
    deleted_at              timestamp with time zone,
    client_id               uuid,

    -- Natural-key uniqueness: one occurrence row per (user, reminder, scheduled civil time).
    -- DB backstop for N6/N7: even if the server-side id-recompute were bypassed, a client
    -- cannot fork one instance into two rows (data-model §5 carry-forward).
    CONSTRAINT uq_reminder_occurrences__natural
        UNIQUE (user_id, reminder_id, scheduled_local_time),

    -- Minute-precision guard: scheduled_local_time must have SECOND = 0.
    -- The uuidv5 hashes "YYYY-MM-DDTHH:mm" — seconds play no role in the id.
    -- Any non-zero-second write is a malformed client; rejected at the application layer
    -- (422) before DB insert; this CHECK is the final backstop.
    CONSTRAINT ck_reminder_occurrences__minute
        CHECK (EXTRACT(SECOND FROM scheduled_local_time) = 0)

);

-- Sync-pull keyset index (database-schema §4.2 / data-model §5).
-- Same three-pattern coverage as ix_supply_items__sync_pull / ix_reminders__sync_pull.
-- INCLUDE omitted for H2 compatibility.
CREATE INDEX ix_reminder_occurrences__sync_pull
    ON reminder_occurrences (user_id, updated_at, id);
