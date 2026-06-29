-- mvp1 — pregnancy_profile: birth_datetime timestamptz → birth_date date
--
-- Architect decision (data-model §3.1 "Birth-event & postpartum counting", OQ-11 RESOLVED):
-- The postpartum clock anchor must be a floating-civil DATE (zoneless YYYY-MM-DD), NOT an
-- absolute-UTC timestamptz. Postpartum day/week counting is civil-day-wise
--   postpartumDays = max(0, civilDaysBetween(birth_date, today))
-- — identical rule to the gestational week counter (FLAG-1) — so "หลังคลอด · สัปดาห์ที่ N"
-- never shifts when the mother travels or crosses a DST boundary.
--
-- Time-of-day of birth is NOT part of the lifecycle anchor. If ever needed it belongs to a
-- future BabyProfile birth record (decoupled baby-age axis, data-model §2), NOT this table.
--
-- Data-safety: birth_datetime was added in V20260629000004 but has never been written
-- (the POST /pregnancy-profile/birth-event endpoint does not yet exist in this branch).
-- The column is NULL in every row, so the DROP loses no data. A direct
-- ALTER COLUMN … TYPE DATE is not supported in PostgreSQL (no implicit timestamptz → date
-- cast), making DROP + ADD the cleanest reversible path.

ALTER TABLE pregnancy_profile DROP COLUMN birth_datetime;
ALTER TABLE pregnancy_profile ADD  COLUMN birth_date date;

-- birth_date: floating-civil (zoneless, proleptic Gregorian — FLAG-1 / data-model §3.1).
--   NULL while lifecycle = 'pregnant'.
--   Set by POST /pregnancy-profile/birth-event (birth-event endpoint, deferred phase).
--   The postpartum-clock anchor: postpartumDays = max(0, civilDaysBetween(birth_date, today)).
--   Application-layer validation (birthDate bounds, data-model OQ-10) — NOT a DB CHECK.
