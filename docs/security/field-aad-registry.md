# Field AAD Registry (frozen tuple contract)

> **Status:** AUTHORITATIVE — single source of truth for the frozen AAD field-tuple
> registry. Both the mobile `FieldCipher` (future write-path) and the server
> `FieldEnvelopeDecryptor` (already shipped) MUST compute a **byte-identical** AAD for
> every encrypted health field listed here.
>
> **Owner:** appsec-engineer · **Reviewer:** appsec-reviewer
> **Envelope version:** `0x01` / AAD prefix `"v1:"` (lock-step)
> **Derived from code (ground truth):**
> - `src/main/java/com/momstarter/encryption/FieldAad.java` (wire format — LOCKED)
> - `src/main/java/com/momstarter/account/AccountExportService.java` (frozen identifiers + every `new FieldAad(...)`)
> - `src/main/resources/db/migration/*.sql` (every `bytea` ciphertext column)
> - `src/test/resources/encryption/golden-vectors.json` (canonical crypto vector)

---

## สรุปภาษาไทยแบบเข้าใจง่าย (สำหรับเจ้าของโปรเจกต์) 🔴

**เรื่องนี้คืออะไร:** เวลาแอปมือถือเข้ารหัสข้อมูลสุขภาพ (เช่น โน้ต, ค่าน้ำหนัก, ชื่อยา)
มันจะผูก "ป้ายกำกับ" ชิ้นหนึ่งเข้ากับข้อมูลที่เข้ารหัส เรียกว่า **AAD**
ป้ายนี้ประกอบด้วย 4 ส่วน: (บัญชีผู้ใช้ + ชื่อกลุ่มข้อมูล + รหัสแถว + ชื่อฟิลด์)

**ทำไมอันตรายมาก 🔴:** ตอน "ถอดรหัส" ฝั่งเซิร์ฟเวอร์จะสร้างป้าย AAD เดียวกันขึ้นมาใหม่
เพื่อตรวจสอบ ถ้าฝั่งมือถือกับฝั่งเซิร์ฟเวอร์สะกดป้ายนี้ **ต่างกันแม้แต่ตัวอักษรเดียว**
(เช่น มือถือใช้ `note_cipher` แต่เซิร์ฟเวอร์ใช้ `note`) การถอดรหัสจะ **ล้มเหลวเงียบๆ**
= **ข้อมูลสุขภาพของคุณแม่หายถาวร กู้คืนไม่ได้ และไม่มี error เตือนในเทสต์ตอนนี้เลย**

**ทางแก้:** ทุกฝ่ายต้องใช้ตารางเดียวกันในเอกสารนี้ ห้ามเดา ห้ามแก้ชื่อ และต้องมี
"golden vector" (ค่าตัวอย่างที่ล็อกไว้) สำหรับทุกฟิลด์ เพื่อพิสูจน์ว่ามือถือกับเซิร์ฟเวอร์
ได้ผลตรงกันแบบไบต์ต่อไบต์ ก่อนขึ้น production

---

## 1. The silent-data-loss threat (plain language)

AES-256-GCM binds the **AAD** into the authentication tag at encrypt time. On decrypt,
the exact same AAD bytes must be supplied or GCM raises a tag-verification failure. In
this system the AAD is *derived* on two independent codebases:

- **Write-path (mobile `FieldCipher`, not yet built):** encrypts the plaintext and must
  build the AAD from these frozen strings.
- **Read-path (server `FieldEnvelopeDecryptor`, shipped):** rebuilds the AAD from the
  same frozen strings to decrypt (see `AccountExportService.export(...)`).

If the two sides disagree on **any** of the four AAD components — even a casing or
suffix difference (`note` vs `note_cipher`, `valueNumeric` vs `value_numeric`) — the tag
fails. There is currently **no test that would catch a cross-platform AAD divergence**,
so the failure surfaces only in production, as unrecoverable health-data loss. This
registry exists to remove all ambiguity.

## 2. The LOCKED wire format

From `FieldAad.toAadBytes()` (do not change):

```
"v1:" + accountId + ":" + collection + ":" + recordId + ":" + fieldName        (UTF-8)
```

- The `"v1:"` prefix is lock-stepped with `FieldEnvelope.VERSION_BYTE = 0x01`.
- The whole string is encoded as **UTF-8** and passed to `Cipher.updateAAD(byte[])`.
- Component order is fixed: `accountId`, then `collection`, then `recordId`, then `fieldName`.

## 3. The registry TABLE (canonical — copy-paste this)

Every encrypted health field currently decrypted by the server. `collection` and
`fieldName` are the **frozen logical identifiers** from `AccountExportService` — they are
NOT the DB column names and NOT the wire/JSON names.

| # | Logical `collection` | Logical `fieldName`      | DB `table.column`                      | `recordId` source        |
|---|----------------------|--------------------------|----------------------------------------|--------------------------|
| 1 | `selfLog`            | `valueNumeric`           | `self_log.value_numeric`               | `self_log.id` (row id)   |
| 2 | `selfLog`            | `valueNumericSecondary`  | `self_log.value_numeric_secondary`     | `self_log.id` (row id)   |
| 3 | `selfLog`            | `valueText`              | `self_log.value_text`                  | `self_log.id` (row id)   |
| 4 | `selfLog`            | `note`                   | `self_log.note_cipher`                 | `self_log.id` (row id)   |
| 5 | `medicationPlan`    | `name`                   | `medication_plan.name_cipher`          | `medication_plan.id`     |
| 6 | `medicationPlan`    | `dose`                   | `medication_plan.dose_cipher`          | `medication_plan.id`     |
| 7 | `medicationLog`     | `note`                   | `medication_log.note_cipher`           | `medication_log.id`      |
| 8 | `kickCountSession`  | `note`                   | `kick_count_session.note_cipher`       | `kick_count_session.id`  |
| 9 | `pregnancyProfile`  | `motherFirstName`        | `pregnancy_profile.mother_first_name_cipher` | **`accountId`** (row-per-account) |
| 10| `pregnancyProfile`  | `motherLastName`         | `pregnancy_profile.mother_last_name_cipher`  | **`accountId`** (row-per-account) |
| 11| `pregnancyProfile`  | `babyName`               | `pregnancy_profile.baby_name_cipher`         | **`accountId`** (row-per-account) |
| 12| `pregnancyProfile`  | `hospitalAdmissionDate`  | `pregnancy_profile.hospital_admission_date_cipher` | **`accountId`** (row-per-account) |
| 13| `pregnancyProfile`  | `hospitalDischargeDate`  | `pregnancy_profile.hospital_discharge_date_cipher` | **`accountId`** (row-per-account) |

> 🔴 **Tuples 9–13 are the row-per-account entries in this registry.** Unlike
> tuples 1–8 (whose `recordId` is the row's own UUID), `pregnancy_profile` stores exactly
> **one row per account**, so its AAD `recordId` **is the `accountId` itself** (RULING 2b).
> This means the same UUID appears **twice** in the AAD string — once in the `accountId`
> slot and once in the `recordId` slot. The mobile `FieldCipher` MUST use `accountId` (NOT
> the `pregnancy_profile` row's own `id`) as `recordId` when encrypting these five fields,
> or every value will fail to decrypt on export (silent health-data loss). This matches
> `AccountExportService.toProfileEntry`, which builds
> `new FieldAad(accountIdStr, "pregnancyProfile", accountIdStr, <fieldName>)`.
>
> Tuples 12–13 (`hospitalAdmissionDate`/`hospitalDischargeDate`) are added at migration
> `V20260710000019` (see `docs/api-spec/pregnancy-summary-design.md` §1.2). Their plaintext is
> a civil date string `YYYY-MM-DD` (UTF-8); the server never parses or validates it. The
> backend slice MUST add the matching frozen constants
> `FIELD_PP_HOSPITAL_ADMISSION = "hospitalAdmissionDate"` and
> `FIELD_PP_HOSPITAL_DISCHARGE = "hospitalDischargeDate"` to `AccountExportService`, and its
> `toProfileEntry` MUST build `new FieldAad(accountIdStr, "pregnancyProfile", accountIdStr,
> "hospitalAdmissionDate"/"hospitalDischargeDate")` — byte-for-byte the tokens above.

**Exact literal AAD string per tuple** (using sample canonical lowercase UUIDs —
`accountId = 11111111-1111-1111-1111-111111111111`; each row id shown per collection):

| # | Literal AAD string (byte-identical on both platforms)                                                                              |
|---|-----------------------------------------------------------------------------------------------------------------------------------|
| 1 | `v1:11111111-1111-1111-1111-111111111111:selfLog:22222222-2222-2222-2222-222222222222:valueNumeric`                                |
| 2 | `v1:11111111-1111-1111-1111-111111111111:selfLog:22222222-2222-2222-2222-222222222222:valueNumericSecondary`                       |
| 3 | `v1:11111111-1111-1111-1111-111111111111:selfLog:22222222-2222-2222-2222-222222222222:valueText`                                   |
| 4 | `v1:11111111-1111-1111-1111-111111111111:selfLog:22222222-2222-2222-2222-222222222222:note`                                        |
| 5 | `v1:11111111-1111-1111-1111-111111111111:medicationPlan:33333333-3333-3333-3333-333333333333:name`                                 |
| 6 | `v1:11111111-1111-1111-1111-111111111111:medicationPlan:33333333-3333-3333-3333-333333333333:dose`                                 |
| 7 | `v1:11111111-1111-1111-1111-111111111111:medicationLog:44444444-4444-4444-4444-444444444444:note`                                  |
| 8 | `v1:11111111-1111-1111-1111-111111111111:kickCountSession:55555555-5555-5555-5555-555555555555:note`                               |
| 9 | `v1:11111111-1111-1111-1111-111111111111:pregnancyProfile:11111111-1111-1111-1111-111111111111:motherFirstName`                    |
| 10| `v1:11111111-1111-1111-1111-111111111111:pregnancyProfile:11111111-1111-1111-1111-111111111111:motherLastName`                     |
| 11| `v1:11111111-1111-1111-1111-111111111111:pregnancyProfile:11111111-1111-1111-1111-111111111111:babyName`                           |
| 12| `v1:11111111-1111-1111-1111-111111111111:pregnancyProfile:11111111-1111-1111-1111-111111111111:hospitalAdmissionDate`              |
| 13| `v1:11111111-1111-1111-1111-111111111111:pregnancyProfile:11111111-1111-1111-1111-111111111111:hospitalDischargeDate`              |

> Note how tuples 9–13 repeat the **same** `accountId` UUID in both the `accountId` and
> `recordId` positions — that is the row-per-account shape (RULING 2b), and it is
> deliberate, not a typo.

> The sample UUIDs are illustrative only. At runtime `accountId` is the account owner's
> UUID and `recordId` is the row's own UUID (see §5). Only the `collection`/`fieldName`
> tokens and the `v1:` prefix and the `:` delimiters are frozen literals.

### 3.1 `recordId` rule per collection (row-id vs accountId)

| `collection`       | `recordId` = ?                    | Row-per-account? |
|--------------------|-----------------------------------|------------------|
| `selfLog`          | the `self_log` row's own id       | No               |
| `medicationPlan`   | the `medication_plan` row's own id| No               |
| `medicationLog`    | the `medication_log` row's own id | No               |
| `kickCountSession` | the `kick_count_session` row's id | No               |
| `pregnancyProfile` | **the `accountId`** (not the row's id) | **Yes** 🔴  |

Tuples 1–8 use the row's own id as `recordId`. `pregnancyProfile` (tuples 9–13) is the
**only** row-per-account collection: because there is exactly one
`pregnancy_profile` row per account, its `recordId` **is the `accountId`** (RULING 2b),
so the `pregnancy_profile.id` UUID never appears in any AAD. This is enforced in code by
`AccountExportService.toProfileEntry`, which passes `accountIdStr` as **both** the first
and third `FieldAad` argument.

## 4. Stability contract (frozen forever)

- `collection` and `fieldName` tokens in §3 are **frozen for the life of envelope v1**.
  They are baked into every GCM tag already written; changing either silently breaks
  every existing ciphertext.
- A rename or crypto-parameter change requires ALL of: (a) a new envelope version byte
  (`0x02`), (b) a new AAD prefix (`"v2:"`), and (c) a **full re-encrypt/migration of every
  affected row**. There is no in-place edit path. The `"v1:"` prefix and version byte
  `0x01` move in lock-step (see `FieldAad` javadoc + `FieldEnvelope.VERSION_BYTE`).
- The golden vectors (§8) may only be regenerated under a version bump — never edited in
  place at v1.

## 5. Delimiter-injection & canonicalization rules

- `collection` and `fieldName` are application-controlled constants and **MUST NOT contain
  `':'`** (the field delimiter). All current tokens are `[A-Za-z]` camelCase — safe.
- `accountId` and `recordId` are **canonical lowercase UUID strings** (hex + `-` only, 36
  chars, no `':'`). Mobile MUST lowercase and hyphenate UUIDs identically to the JVM
  `UUID.toString()` output the server uses (`AccountExportService` calls
  `s.getId().toString()` / `userId.toString()`).
- Do not URL-encode, trim, pad, or Unicode-normalize any component. The AAD is the raw
  UTF-8 of the concatenation exactly as shown.

## 6. Cross-check findings (gaps & risks)

### G1 — self_log ciphertext columns are NOT `_cipher`-suffixed 🟡

`self_log.value_numeric`, `value_numeric_secondary`, and `value_text` are genuine `bytea`
ciphertext columns (holding encrypted health values) but do **not** carry the `_cipher`
suffix. A standard audit sweep of `grep -rn "_cipher"` therefore **misses three real
encrypted columns**. They ARE correctly registered in the AAD tuples (rows 1–3) and are
decrypted by the server, so this is **not** a functional break — it is an **audit
blind-spot**.

- **Risk:** a future reviewer or SAST rule keyed on the `_cipher` naming convention could
  wrongly conclude these columns are plaintext, or a new self_log-style table could add a
  bytea value column that never gets registered here.
- **Impact if ignored:** an unregistered future ciphertext column ships with no frozen
  tuple → mobile and server derive AADs independently → silent decrypt failure.
- **Fix (step-by-step):**
  1. Treat "encrypted column" as "any `bytea` column that is not `wrapped_dek`", NOT
     "column ending in `_cipher`". Audit with `grep -rn "bytea" db/migration`.
  2. Add a one-line comment on each of the three columns in
     `V20260703000015__mvp1_self_logs.sql` noting they are ciphertext registered in this
     doc (rows 1–3).
  3. Whenever a new `bytea` health column is added, adding its row to §3 is a PR blocker.

### G2 — `pregnancy_profile` name + hospital-stay fields now encrypted (row-per-account) — RESOLVED 🟢

**Update (2026-07):** `pregnancy_profile` now stores five encrypted fields — the three name
fields `mother_first_name_cipher`, `mother_last_name_cipher`, `baby_name_cipher` (tuples
9–11) plus the two hospital-stay date fields `hospital_admission_date_cipher`,
`hospital_discharge_date_cipher` (tuples 12–13, migration `V20260710000019`) — all decrypted
by `AccountExportService.toProfileEntry`. These are registered as tuples **9–13** in §3 with
the row-per-account `recordId = accountId` rule (RULING 2b) and each has a committed golden
vector (§8). This closes the former gap.

- Remaining plaintext fields (`birth_note`, `edd`, `delivery_type`, etc.) are still exported
  directly (`p.getBirthNote()`) and are **not** encrypted; they have no AAD tuple by design.
- **Invariant kept:** the row-per-account shape (`recordId = accountId`) must never be
  copied to a genuinely per-row collection, and vice-versa. Tuples 9–13 are the sole
  row-per-account entries; if a new `pregnancy_profile` cipher field is added, it inherits
  `recordId = accountId` and needs its own golden vector before mobile encrypts it.
- **Backend follow-up (tuples 12–13):** the golden vectors and drift-guard for
  `hospitalAdmissionDate`/`hospitalDischargeDate` are committed here, but their frozen
  constants `FIELD_PP_HOSPITAL_ADMISSION = "hospitalAdmissionDate"` /
  `FIELD_PP_HOSPITAL_DISCHARGE = "hospitalDischargeDate"` are **not yet** in
  `AccountExportService`. The drift-guard test (§8) is a deliberate RED until the backend
  slice adds those two constants and wires `toProfileEntry` to decrypt the two cipher columns.

### G3 — `account_dek.wrapped_dek` is `bytea` but is NOT a field envelope 🟢

`account_dek.wrapped_dek` is a `bytea` column but it is the **KMS-wrapped DEK**, not an
AES-GCM field envelope. It has **no AAD tuple** and must never be added to this registry.
Documented here explicitly so the §6/G1 "every bytea is a ciphertext field" heuristic has
its one legitimate exception.

### G4 — no cross-platform AAD divergence test exists yet 🔴

There is currently no test asserting that the mobile-derived AAD equals the
server-derived AAD for each tuple. The whole silent-loss risk is unguarded.

- **Fix:** the backend task that follows this doc MUST add a committed golden vector per
  tuple (§8) and both server `FieldEnvelopeDecryptor` tests and mobile `FieldCipher` tests
  MUST assert against those exact vectors. See §8.

## 7. Ruling: `expenses/note` — demo vector, NOT a registry tuple

`golden-vectors.json` pins one demo tuple `expenses/note`. The schema check is decisive:
`expenses` has **no `bytea`/`*_cipher` column** — `V20260703000014__mvp1_expenses.sql` and
`Expense.java` both state expenses are **non-health, plaintext under KMS at-rest volume
encryption**, and `AccountExportService` exports `e.getNote()` as **plaintext** (never
decrypted).

**Ruling:** `expenses/note` is **(b) a canonical crypto demo vector only** — it exercises
the envelope/AAD math with stable placeholder UUIDs. It is **NOT** a real registered
encrypted field and MUST NOT be treated as one. It is intentionally absent from the §3
table. If field-level encryption is ever added to `expenses` (architect sign-off required
per the migration note), it must then be registered in §3 with real golden vectors — the
demo vector does not satisfy that requirement.

## 8. Golden-vector coverage requirement

- A committed golden vector MUST exist for **every one of the 13 tuples** in §3. Coverage
  is currently **13/13** (`golden-vectors.json` → `registry_vectors.vectors`), locked by
  `FieldAadRegistryGoldenVectorTest` (encrypt-golden-match / roundtrip / tamper-negative /
  drift-guard per tuple). The `expenses/note` demo vector is separate and NOT counted here.
  For tuples 12–13 the encrypt-golden-match / roundtrip / tamper assertions pass today; the
  **drift-guard is a deliberate RED** until the backend adds the `FIELD_PP_HOSPITAL_ADMISSION`
  / `FIELD_PP_HOSPITAL_DISCHARGE` constants (see §6 G2).
- Each vector fixes: `dek_hex`, `iv_hex`, `plaintext`, the exact `aad_string`, and the
  `expected_envelope_hex` / `base64_wire_value`.
- **Both** sides must assert against the same vectors: server `FieldEnvelopeDecryptor`
  tests (decrypt vector → expected plaintext, and tamper-the-AAD → tag fail) AND mobile
  `FieldCipher` tests (encrypt with fixed DEK+IV → byte-identical envelope). Byte-identical
  output across JVM / Node / device is the launch gate (per `golden-vectors.json._impls`).
- Vectors are regenerated ONLY on a version bump (§4).

## 9. Mobile write-path obligations (`FieldCipher`)

The mobile `FieldCipher` MUST, for each field it encrypts:

- Use the **exact frozen `collection` and `fieldName` strings from §3** — nothing else.
- Build the AAD via the §2 format: `"v1:"` + accountId + `":"` + collection + `":"` +
  recordId + `":"` + fieldName, UTF-8, in that order.
- Use `recordId` per §3.1: the row's own UUID for tuples 1–8, but **the `accountId`** for
  the row-per-account `pregnancyProfile` tuples 9–13 (RULING 2b).
- Lowercase/canonicalize UUIDs to match `UUID.toString()` (§5).

The mobile `FieldCipher` MUST **NOT**:

- Use the wire/JSON field names (e.g. the sync payload key) as `fieldName`.
- Use the DB **column** names — e.g. NOT `note_cipher` (use `note`), NOT `value_numeric`
  (use `valueNumeric`), NOT `name_cipher` (use `name`), NOT `dose_cipher` (use `dose`).
- Use snake_case where the registry uses camelCase (`valueNumericSecondary`, not
  `value_numeric_secondary`).
- Use the DB **table** name as `collection` — e.g. NOT `self_log` / `kick_count_session`
  (use `selfLog` / `kickCountSession`), NOT `medication_plan` (use `medicationPlan`).
- Invent a tuple for any field not in §3 (e.g. `expenses/note`, or any `pregnancyProfile`
  field OTHER than the five registered fields — three names + two hospital-stay dates) —
  encrypting an unregistered field is prohibited until it is added here.
- Use the `pregnancy_profile` row's own `id` as `recordId` for tuples 9–13 — for those
  row-per-account fields `recordId` MUST be the `accountId`.
- Insert `':'`, whitespace, padding, or perform Unicode normalization on any component.

## 10. Security invariants (never violate)

- Never log, persist, or emit the plaintext DEK, the raw cipher bytes, or the decrypted
  plaintext — on success **or** on error (mirrors `AccountExportService` / `dispatchDecrypt`).
- The AAD string itself contains only ids + logical names (no secrets) and is safe to
  document, but do not log it alongside ciphertext in a way that aids oracle attacks.
