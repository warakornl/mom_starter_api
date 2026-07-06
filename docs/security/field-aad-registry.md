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

**None** of the currently-encrypted collections are row-per-account, so **every** current
tuple uses the row's own id as `recordId`. The `recordId = accountId` rule documented in
`FieldAad` (for `pregnancyProfile`) is a **future** convention — `pregnancy_profile` has
**no ciphertext column today** (see §6, gap G2). Do not implement it until such a column
is added and a tuple is registered here.

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

### G2 — `pregnancy_profile` has a documented AAD convention but no ciphertext column 🟢

`FieldAad`'s javadoc gives `pregnancyProfile` (with `recordId = accountId`) as the
row-per-account example, but `pregnancy_profile` currently stores `birth_note`/health
fields as **plaintext** (exported via `p.getBirthNote()` directly — no decrypt). There is
no cipher column and no registered tuple.

- **Risk:** low today (no data at stake). The concern is *future* — if field-level
  encryption is added to `pregnancy_profile`, the implementer must register the tuple here
  with `recordId = accountId` and add golden vectors, not copy a row-id pattern by habit.
- **Fix:** when/if a `pregnancy_profile` field becomes encrypted, add its row to §3 using
  the row-per-account `recordId = accountId` rule and a golden vector before mobile builds
  the write-path.

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

- A committed golden vector MUST exist for **every one of the 8 tuples** in §3 (the
  current file only pins the `expenses/note` demo vector, so coverage is **0/8** for real
  tuples — this drives the follow-up backend task).
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
- Use `recordId` per §3.1 (the row's own UUID for all current collections).
- Lowercase/canonicalize UUIDs to match `UUID.toString()` (§5).

The mobile `FieldCipher` MUST **NOT**:

- Use the wire/JSON field names (e.g. the sync payload key) as `fieldName`.
- Use the DB **column** names — e.g. NOT `note_cipher` (use `note`), NOT `value_numeric`
  (use `valueNumeric`), NOT `name_cipher` (use `name`), NOT `dose_cipher` (use `dose`).
- Use snake_case where the registry uses camelCase (`valueNumericSecondary`, not
  `value_numeric_secondary`).
- Use the DB **table** name as `collection` — e.g. NOT `self_log` / `kick_count_session`
  (use `selfLog` / `kickCountSession`), NOT `medication_plan` (use `medicationPlan`).
- Invent a tuple for any field not in §3 (e.g. `expenses/note`, any `pregnancyProfile`
  field) — encrypting an unregistered field is prohibited until it is added here.
- Insert `':'`, whitespace, padding, or perform Unicode normalization on any component.

## 10. Security invariants (never violate)

- Never log, persist, or emit the plaintext DEK, the raw cipher bytes, or the decrypted
  plaintext — on success **or** on error (mirrors `AccountExportService` / `dispatchDecrypt`).
- The AAD string itself contains only ids + logical names (no secrets) and is safe to
  document, but do not log it alongside ciphertext in a way that aids oracle attacks.
