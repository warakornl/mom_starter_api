package com.momstarter.pregnancy;

import com.momstarter.error.ApiException;
import com.momstarter.pregnancy.dto.BirthEventInput;
import com.momstarter.pregnancy.dto.PregnancyProfileInput;
import com.momstarter.pregnancy.dto.PregnancyProfileResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Business logic for GET/PUT /pregnancy-profile and POST /pregnancy-profile/birth-event.
 *
 * <p>Validates input, checks the consent gate (via the {@link ConsentChecker} seam — currently
 * always-granted pending the account/consents feature), and enforces optimistic concurrency
 * via the {@code If-Match} header.
 *
 * <p>Supports both lifecycle modes ({@code pregnant} and {@code postpartum}):
 * <ul>
 *   <li>{@link #get} / {@link #put} return the appropriate snapshot for whichever
 *       lifecycle state the profile is currently in.</li>
 *   <li>{@link #recordBirthEvent} implements the {@code pregnant → postpartum} transition
 *       (api-contract "Birth-event &amp; postpartum counting — PINNED", OQ-8/10/11/12/13).</li>
 * </ul>
 */
@Service
public class PregnancyProfileService {

    private static final String GENERAL_HEALTH = "general_health";

    /**
     * Ciphertext byte cap for name cipher fields (Base64-decoded size).
     * Mirrors the {@code note_too_large} cap used by KickCountSessionSyncCollection and peers.
     * The server cannot validate plaintext length inside a ciphertext, so a generous but bounded
     * byte-cap guards against oversized payloads.
     */
    static final int MAX_NAME_CIPHER_BYTES = 8192;

    private final PregnancyProfileRepository repository;
    private final ConsentChecker consentChecker;

    public PregnancyProfileService(PregnancyProfileRepository repository,
                                   ConsentChecker consentChecker) {
        this.repository = repository;
        this.consentChecker = consentChecker;
    }

    // -------------------------------------------------------------------------
    // GET
    // -------------------------------------------------------------------------

    /**
     * Returns the live (non-deleted) profile for the user, with a derived snapshot
     * computed from {@code clientDate}.
     *
     * <p>Returns a gestational-age snapshot when {@code lifecycle = "pregnant"} and a
     * postpartum snapshot when {@code lifecycle = "postpartum"} (data-model §3.1).
     *
     * @param userId     authenticated user id (JWT subject)
     * @param clientDate the civil "today" for the snapshot ({@code X-Client-Date} or UTC fallback)
     * @return the response DTO
     * @throws ApiException 404 {@code not_found} when no live profile exists
     */
    @Transactional(readOnly = true)
    public PregnancyProfileResponse get(UUID userId, LocalDate clientDate) {
        PregnancyProfile profile = requireProfile(userId);
        return buildResponse(profile, clientDate);
    }

    // -------------------------------------------------------------------------
    // PUT
    // -------------------------------------------------------------------------

    /**
     * Sealed result returned by {@link #put} to distinguish create (201) from update (200).
     */
    public sealed interface PutResult permits PutResult.Created, PutResult.Updated {
        record Created(PregnancyProfileResponse profile) implements PutResult {}
        record Updated(PregnancyProfileResponse profile) implements PutResult {}
    }

    /**
     * Creates or updates the pregnancy profile.
     *
     * <p>Rules (api-contract B2 / OQ-4/5/6/7/9):
     * <ol>
     *   <li>XOR validation: exactly one of {@code edd} / {@code currentWeek} must be present.</li>
     *   <li>Consent gate: {@code general_health} must be granted (403 if not).</li>
     *   <li>{@code currentWeek → edd} back-computation: {@code edd = clientDate + (280 − N*7)}.</li>
     *   <li>EDD plausibility window: {@code clientDate−28d ≤ edd ≤ clientDate+308d} → 422.</li>
     *   <li>Name cipher byte-cap: any name field ciphertext decoded to &gt; 8 KB → 422 name_too_large.</li>
     *   <li>Create (no existing row): persist new profile → return Created (HTTP 201).</li>
     *   <li>Update (row exists):
     *       <ul>
     *         <li>{@code If-Match} header absent → 428.</li>
     *         <li>Version mismatch → {@link StaleVersionException} (caller returns 409).</li>
     *         <li>No-op (OQ-9 SCOPED): EDD unchanged AND no name key present → no-op, version NOT bumped.
     *             If ANY name key is present (value or explicit null), it is a REAL mutation:
     *             persist + bump version even when EDD is unchanged.</li>
     *         <li>EDD changed OR any name key present → persist, bump version, return Updated (200).</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <h2>Name-field null/absent/value semantics</h2>
     * <ul>
     *   <li><b>Absent</b> ({@link Optional} field is Java {@code null}) → leave column unchanged.</li>
     *   <li><b>Explicit JSON null</b> ({@link Optional#empty()}) → clear column to {@code NULL}.</li>
     *   <li><b>Value</b> ({@link Optional#of(Object)}) → Base64-decode and store as {@code bytea}.</li>
     * </ul>
     *
     * @param userId     authenticated user id
     * @param input      request body
     * @param ifMatch    raw {@code If-Match} header value (may be null)
     * @param clientDate civil "today" ({@code X-Client-Date} or UTC fallback)
     * @return {@link PutResult.Created} or {@link PutResult.Updated}
     * @throws ApiException          422 validation_error (incl. name_too_large details), 403 consent_required, 428 precondition_required
     * @throws StaleVersionException 409 (version mismatch; caller attaches current profile body)
     */
    @Transactional
    public PutResult put(UUID userId, PregnancyProfileInput input,
                         String ifMatch, LocalDate clientDate) {

        // 1 — XOR validation (both present or both absent → 422)
        boolean hasEdd         = input != null && input.getEdd() != null;
        boolean hasCurrentWeek = input != null && input.getCurrentWeek() != null;
        if (hasEdd == hasCurrentWeek) {
            throw new ApiException(422, "validation_error");
        }

        // 2 — Consent gate (general_health). Currently always-granted via seam.
        if (!consentChecker.isGranted(userId, GENERAL_HEALTH)) {
            throw new ApiException(403, "consent_required", GENERAL_HEALTH);
        }

        // 3 — Resolve EDD and eddBasis
        LocalDate edd;
        String eddBasis;
        if (hasEdd) {
            edd      = input.getEdd();
            eddBasis = "due_date";
        } else {
            int week = input.getCurrentWeek();
            if (week < 1 || week > 42) {
                throw new ApiException(422, "validation_error");
            }
            edd      = clientDate.plusDays(280L - (long) week * 7);
            eddBasis = "current_week";
        }

        // 4 — EDD plausibility window: clientDate−28d ≤ edd ≤ clientDate+308d (OQ-6)
        LocalDate minEdd = clientDate.minusDays(28);
        LocalDate maxEdd = clientDate.plusDays(308);
        if (edd.isBefore(minEdd) || edd.isAfter(maxEdd)) {
            throw new ApiException(422, "validation_error");
        }

        // 5 — Validate name cipher byte-caps BEFORE any DB mutation (fail-fast).
        // Any name ciphertext that decodes to > MAX_NAME_CIPHER_BYTES is rejected 422 name_too_large.
        // Validation runs whether or not a row already exists (defensive ordering).
        if (input != null) {
            validateNameCipherSize(input.getMotherFirstName());
            validateNameCipherSize(input.getMotherLastName());
            validateNameCipherSize(input.getBabyName());
        }

        // Detect whether ANY name key is present in the request (null = absent = no-op-eligible).
        // An Optional (empty or with value) = present = real mutation regardless of value.
        boolean anyNameKeyPresent = input != null
                && (input.getMotherFirstName() != null
                    || input.getMotherLastName() != null
                    || input.getBabyName() != null);

        // 6 — Look up any existing row, including a soft-deleted tombstone.
        java.util.Optional<PregnancyProfile> anyExisting = repository.findByUserId(userId);

        if (anyExisting.isEmpty()) {
            // 6a — No row at all: insert new profile
            PregnancyProfile profile = new PregnancyProfile();
            profile.setUserId(userId);
            profile.setEdd(edd);
            profile.setEddBasis(eddBasis);
            applyNameCiphers(profile, input);
            PregnancyProfile saved = repository.saveAndFlush(profile);
            return new PutResult.Created(buildResponse(saved, clientDate));
        }

        PregnancyProfile profile = anyExisting.get();

        if (profile.getDeletedAt() != null) {
            // 6b — Tombstone: resurrect the row (treat as creation for the caller)
            profile.setDeletedAt(null);
            profile.setEdd(edd);
            profile.setEddBasis(eddBasis);
            applyNameCiphers(profile, input);
            PregnancyProfile saved = repository.saveAndFlush(profile);
            return new PutResult.Created(buildResponse(saved, clientDate));
        }

        // 6c — Update: If-Match is MANDATORY
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(428, "precondition_required");
        }

        long clientVersion = parseIfMatch(ifMatch);
        if (clientVersion != profile.getVersion()) {
            throw new StaleVersionException(buildResponse(profile, clientDate));
        }

        // No-op (OQ-9 SCOPED EXCEPTION): same EDD AND no name key present → true no-op.
        // If ANY name key is present (even explicit null), it is a REAL mutation: persist + bump.
        // NEVER byte-diff the name ciphers (random-IV → same plaintext produces different bytes).
        if (!anyNameKeyPresent && edd.equals(profile.getEdd())) {
            return new PutResult.Updated(buildResponse(profile, clientDate));
        }

        // Real update: persist EDD change and/or name cipher changes.
        profile.setEdd(edd);
        profile.setEddBasis(eddBasis);
        applyNameCiphers(profile, input);
        PregnancyProfile saved = repository.saveAndFlush(profile);
        return new PutResult.Updated(buildResponse(saved, clientDate));
    }

    // -------------------------------------------------------------------------
    // POST /pregnancy-profile/birth-event
    // -------------------------------------------------------------------------

    /**
     * Records a birth event, transitioning {@code lifecycle: pregnant → postpartum}.
     *
     * <p>Preconditions, in order:
     * <ol>
     *   <li>Profile must exist (live, non-deleted) — {@code 404 not_found} if absent.</li>
     *   <li>{@code lifecycle == "ended"} — {@code 409 invalid_lifecycle_state (details:"ended")}.</li>
     *   <li>Consent gate: {@code general_health} must be granted — {@code 403 consent_required}.</li>
     *   <li>{@code If-Match} header — absent → {@code 428}; stale version → {@code 409} with
     *       the current authoritative profile in the body.</li>
     *   <li>Birth-date bounds (application-layer, non-judgmental typo-guards — OQ-10):
     *       <ul>
     *         <li>{@code birthDate ≤ clientDate} (no future birth)</li>
     *         <li>{@code birthDate ≥ edd − 126 days} (sanity floor ≈ 22 wk gestation)</li>
     *       </ul>
     *       Violation → {@code 422 validation_error}.
     *   </li>
     * </ol>
     *
     * <p>Post-condition when already {@code postpartum} (OQ-12/PP6 idempotency):
     * <ul>
     *   <li>{@code birthDate} equals stored {@code birthDate} → <strong>no-op</strong>:
     *       return the current record with {@code version} NOT bumped.</li>
     *   <li>{@code birthDate} differs → <strong>correction</strong>: update and bump
     *       {@code version} → {@code 200}. (OQ-13/PP1)</li>
     * </ul>
     *
     * <p><strong>deliveryType / birthNote TODO (security-compliance):</strong> These fields are
     * stored as plaintext {@code varchar}/{@code text} for the test phase. The api-contract marks
     * them "client-encrypted {@code bytea}" — encryption is deferred to the security-compliance
     * phase. The no-op equality check already compares {@code birthDate} ONLY (not the cipher
     * fields) per OQ-12/PP6, so the deferred encryption does not break the idempotency rule.
     *
     * @param userId     authenticated user id
     * @param input      request body ({@code birthDate} required; {@code deliveryType/birthNote} optional)
     * @param ifMatch    raw {@code If-Match} header value (may be null)
     * @param clientDate civil "today" ({@code X-Client-Date} or UTC fallback)
     * @return the updated/current profile as a postpartum response snapshot
     * @throws ApiException          404, 409 (lifecycle/state), 403, 428, 422
     * @throws StaleVersionException 409 (version mismatch; caller attaches current profile body)
     */
    @Transactional
    public PregnancyProfileResponse recordBirthEvent(UUID userId, BirthEventInput input,
                                                     String ifMatch, LocalDate clientDate) {

        // 1 — Profile must exist
        PregnancyProfile profile = requireProfile(userId);

        // 2 — lifecycle == "ended" is a terminal state (OQ-PP7 — write path deferred)
        if ("ended".equals(profile.getLifecycle())) {
            throw new ApiException(409, "invalid_lifecycle_state", "ended");
        }

        // 3 — Consent gate (general_health — same gate as PUT /pregnancy-profile)
        if (!consentChecker.isGranted(userId, GENERAL_HEALTH)) {
            throw new ApiException(403, "consent_required", GENERAL_HEALTH);
        }

        // 4 — If-Match required (B2: mandatory on all direct-REST mutations to existing rows)
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(428, "precondition_required");
        }
        long clientVersion = parseIfMatch(ifMatch);
        if (clientVersion != profile.getVersion()) {
            throw new StaleVersionException(buildResponse(profile, clientDate));
        }

        // 5 — Validate birthDate (required field)
        LocalDate birthDate = (input != null) ? input.birthDate() : null;
        if (birthDate == null) {
            throw new ApiException(422, "validation_error");
        }
        // 5a — No future birth: birthDate ≤ clientDate(today)
        if (birthDate.isAfter(clientDate)) {
            throw new ApiException(422, "validation_error");
        }
        // 5b — Sanity floor: birthDate ≥ edd − 126 days (≈ 22 wk gestation — OQ-10)
        if (birthDate.isBefore(profile.getEdd().minusDays(126))) {
            throw new ApiException(422, "validation_error");
        }

        // 6 — Already postpartum: content-level idempotency (OQ-12/PP6)
        if ("postpartum".equals(profile.getLifecycle())) {
            if (birthDate.equals(profile.getBirthDate())) {
                // Exact same birthDate → true no-op: return current record, version NOT bumped.
                // No-op equality compares birthDate ONLY — never deliveryType/birthNote (those
                // are client-encrypted with a random IV in the production path, so byte-equality
                // would false-negative; see OQ-12 note in api-contract).
                PostpartumAge pa = PostpartumAge.compute(profile.getBirthDate(), clientDate);
                return PregnancyProfileResponse.of(profile, pa);
            }
            // Different birthDate: typo correction (OQ-13/PP1) — update and bump version
            profile.setBirthDate(birthDate);
            applyOptionalFields(profile, input);
            PregnancyProfile saved = repository.saveAndFlush(profile);
            PostpartumAge pa = PostpartumAge.compute(saved.getBirthDate(), clientDate);
            return PregnancyProfileResponse.of(saved, pa);
        }

        // 7 — pregnant → postpartum transition (the primary path)
        profile.setLifecycle("postpartum");
        profile.setBirthDate(birthDate);
        applyOptionalFields(profile, input);
        PregnancyProfile saved = repository.saveAndFlush(profile);
        PostpartumAge pa = PostpartumAge.compute(saved.getBirthDate(), clientDate);
        return PregnancyProfileResponse.of(saved, pa);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the appropriate response DTO based on the profile's current lifecycle.
     * Returns a gestational-age snapshot when pregnant, a postpartum snapshot when postpartum.
     */
    private PregnancyProfileResponse buildResponse(PregnancyProfile profile, LocalDate clientDate) {
        if ("postpartum".equals(profile.getLifecycle())) {
            PostpartumAge pa = PostpartumAge.compute(profile.getBirthDate(), clientDate);
            return PregnancyProfileResponse.of(profile, pa);
        }
        GestationalAge ga = GestationalAge.compute(profile.getEdd(), clientDate, profile.getLifecycle());
        return PregnancyProfileResponse.of(profile, ga);
    }

    /**
     * Applies optional {@code deliveryType} and {@code birthNote} fields when present.
     *
     * <p>Only writes the field when the request carries a non-null value. A same-birthDate
     * no-op re-send does not reach this method (returns early before), so this is only called
     * on the initial transition and on birthDate-changing corrections.
     *
     * <p>TODO security-compliance: replace with encrypted-bytea write when field-level
     * encryption ships (see {@link com.momstarter.pregnancy.dto.BirthEventInput} TODO block).
     */
    private static void applyOptionalFields(PregnancyProfile profile, BirthEventInput input) {
        if (input == null) return;
        if (input.deliveryType() != null) {
            profile.setDeliveryType(input.deliveryType());
        }
        if (input.birthNote() != null) {
            profile.setBirthNote(input.birthNote());
        }
    }

    /**
     * Applies name cipher fields from the request to the profile entity.
     * Three-way semantics per {@link PregnancyProfileInput} contract:
     * <ul>
     *   <li>Field absent (Java {@code null}) → leave column unchanged.</li>
     *   <li>{@code Optional.empty()} (explicit JSON null) → set column to {@code null} (clear).</li>
     *   <li>{@code Optional.of(base64)} → Base64-decode and store as {@code bytea}.</li>
     * </ul>
     *
     * <p>Called on both INSERT and UPDATE paths. Idempotent.
     * Byte-cap validation has already been performed before this method is called.
     */
    private static void applyNameCiphers(PregnancyProfile profile, PregnancyProfileInput input) {
        if (input == null) return;
        applyNameCipher(profile::setMotherFirstNameCipher, input.getMotherFirstName());
        applyNameCipher(profile::setMotherLastNameCipher, input.getMotherLastName());
        applyNameCipher(profile::setBabyNameCipher, input.getBabyName());
    }

    /**
     * Applies a single name cipher field using the three-way semantics.
     *
     * @param setter     the entity setter for the target cipher column
     * @param nameField  {@code null} = absent (no-op), {@code Optional.empty()} = clear,
     *                   {@code Optional.of(b64)} = set decoded bytes
     */
    private static void applyNameCipher(Consumer<byte[]> setter, Optional<String> nameField) {
        if (nameField == null) {
            return; // absent key — leave column unchanged
        }
        if (!nameField.isPresent()) {
            setter.accept(null); // explicit JSON null — clear to NULL
        } else {
            // Value present: Base64-decode and store (cap already validated)
            setter.accept(Base64.getDecoder().decode(nameField.get()));
        }
    }

    /**
     * Validates that a name cipher field, when present and non-null, decodes to at most
     * {@value #MAX_NAME_CIPHER_BYTES} bytes. Throws 422 {@code validation_error} with
     * details {@code name_too_large} when exceeded.
     *
     * <p>Absent fields (Java {@code null}) and explicit nulls ({@code Optional.empty()}) are
     * skipped — there are no bytes to validate.
     *
     * @param nameField the name field optional from the DTO
     * @throws ApiException 422 validation_error (details: name_too_large) if cap exceeded,
     *                      or 422 validation_error (no details) if not valid Base64
     */
    private static void validateNameCipherSize(Optional<String> nameField) {
        if (nameField == null || !nameField.isPresent()) {
            return; // absent or explicit null — no bytes to validate
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(nameField.get());
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, "validation_error");
        }
        if (decoded.length > MAX_NAME_CIPHER_BYTES) {
            throw new ApiException(422, "validation_error", "name_too_large");
        }
    }

    private PregnancyProfile requireProfile(UUID userId) {
        return repository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ApiException(404, "not_found"));
    }

    /**
     * Parses the raw {@code If-Match} header value. HTTP ETag convention wraps the value in
     * double quotes (e.g. {@code "0"}), so we strip them before parsing.
     *
     * <ul>
     *   <li>Header absent (null/blank) → caller throws {@code 428} before reaching here.</li>
     *   <li>Header present but unparseable (e.g. {@code "abc"}) → {@code 412 precondition_failed}.</li>
     * </ul>
     */
    private static long parseIfMatch(String ifMatch) {
        try {
            return Long.parseLong(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new ApiException(412, "precondition_failed");
        }
    }
}
