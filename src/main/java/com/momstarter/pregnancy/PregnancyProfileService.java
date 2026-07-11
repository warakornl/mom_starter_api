package com.momstarter.pregnancy;

import com.momstarter.error.ApiException;
import com.momstarter.pregnancy.dto.BirthEventInput;
import com.momstarter.pregnancy.dto.LossEventInput;
import com.momstarter.pregnancy.dto.PregnancyProfileInput;
import com.momstarter.pregnancy.dto.PregnancyProfileResponse;
import com.momstarter.reminder.ReminderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

    /**
     * Ciphertext byte cap for hospital-stay date cipher fields (Base64-decoded size).
     * Mirrors {@link #MAX_NAME_CIPHER_BYTES} — same generous bound for the same reason.
     * Civil date strings are short (10 bytes plain + ~29 bytes GCM envelope = ~39 bytes),
     * so 8192 bytes allows for any future extended encryption wrapper while guarding against
     * clearly oversized payloads.
     * Sub-error code on violation: {@code hospital_date_too_large}.
     */
    static final int MAX_HOSPITAL_DATE_CIPHER_BYTES = 8192;

    private final PregnancyProfileRepository repository;
    private final ConsentChecker consentChecker;
    private final ReminderRepository reminderRepository;

    public PregnancyProfileService(PregnancyProfileRepository repository,
                                   ConsentChecker consentChecker,
                                   ReminderRepository reminderRepository) {
        this.repository = repository;
        this.consentChecker = consentChecker;
        this.reminderRepository = reminderRepository;
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
        LocalDate birthDate = (input != null) ? input.getBirthDate() : null;
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

        // 5c — Hospital-date byte-cap validation (fail-fast, before any DB mutation).
        // Ciphers are random-IV bytea — the server never parses or validates the date content.
        // Temporal validation (discharge >= admission, <= today) is CLIENT-SIDE ONLY.
        // The byte-cap guards against oversized payloads (sub-code: hospital_date_too_large).
        if (input != null) {
            validateHospitalDateCipherSize(input.getHospitalAdmissionDate());
            validateHospitalDateCipherSize(input.getHospitalDischargeDate());
        }

        // Detect whether ANY hospital-stay key is present in the request.
        // Presence-of-key (value OR explicit null) suppresses the no-op short-circuit
        // (contract L227 — load-bearing pin). NEVER byte-diff the cipher bytes (random-IV
        // means the same plaintext produces different bytes on every encrypt call).
        boolean anyHospitalKeyPresent = input != null
                && (input.getHospitalAdmissionDate() != null
                    || input.getHospitalDischargeDate() != null);

        // 6 — Already postpartum: content-level idempotency (OQ-12/PP6)
        if ("postpartum".equals(profile.getLifecycle())) {
            if (birthDate.equals(profile.getBirthDate()) && !anyHospitalKeyPresent) {
                // Exact same birthDate AND no hospital-stay key present → true no-op:
                // return current record, version NOT bumped.
                // No-op equality compares birthDate ONLY — never deliveryType/birthNote nor
                // cipher fields (those are client-encrypted with a random IV in the production
                // path, so byte-equality would false-negative; see OQ-12 note in api-contract).
                PostpartumAge pa = PostpartumAge.compute(profile.getBirthDate(), clientDate);
                return PregnancyProfileResponse.of(profile, pa);
            }
            // Different birthDate OR any hospital-stay key present:
            // both are real mutations → update and bump version.
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
    // POST /pregnancy-profile/loss-event
    // -------------------------------------------------------------------------

    /** Light, non-judgemental sanity floor: {@code edd - 301 days} (functional-spec §7.2). */
    private static final long LOSS_DATE_FLOOR_DAYS_BEFORE_EDD = 301L;

    /**
     * Records a pregnancy-loss event, transitioning {@code lifecycle: pregnant -> ended}
     * (functional-spec pregnancy-loss-recording-functional-spec.md §7.1-§7.3, LOSS-INV-1..12).
     *
     * <p>Preconditions, in the EXACT order the functional spec's truth-table requires:
     * <ol>
     *   <li>Profile must exist (live, non-deleted) - {@code 404 not_found} if absent.</li>
     *   <li>Consent gate: {@code general_health} must be granted - {@code 403 consent_required}.</li>
     *   <li>{@code If-Match} header - absent -&gt; {@code 428}; unparseable -&gt; {@code 412};
     *       stale (well-formed but mismatched) -&gt; {@code 409} with the current profile.</li>
     *   <li>Lifecycle guard: {@code postpartum} -&gt; {@code 409 invalid_lifecycle_state
     *       details:"postpartum"}; {@code ended} -&gt; idempotent (below); {@code pregnant} -&gt; apply.</li>
     *   <li>{@code lossDate} validation (§7.2): malformed/time-component -&gt;
     *       {@code 422 validation_error details:"loss_date_malformed"}; out of the light sanity
     *       window (future, or before {@code edd - 301d}) -&gt;
     *       {@code 422 validation_error details:"loss_date_range"}.</li>
     * </ol>
     *
     * <p><strong>LOSS-INV-3 atomicity</strong>: the enum flip, {@code loss_date} write,
     * {@code version}/{@code updated_at} bump on the profile, AND the reminder sweep (deactivate
     * every {@code survives_ended=false AND active=true AND deleted_at IS NULL} reminder, bumping
     * each swept reminder's {@code version}/{@code updated_at}) all happen inside this single
     * {@code @Transactional} method against the real Postgres/H2-PG-mode store - a genuine DB
     * transaction, not an in-memory map. If any part throws, Spring rolls back the WHOLE thing.
     *
     * <p><strong>Idempotency (§7.6)</strong>: already-{@code ended} + same {@code lossDate}
     * (both-NULL counts as equal) -&gt; {@code 200}, {@code version} NOT bumped, sweep NOT re-run.
     * Already-{@code ended} + differing {@code lossDate} -&gt; corrects the date, bumps
     * {@code version}, sweep NOT re-run (reminders already swept).
     *
     * @param userId     authenticated user id
     * @param input      request body ({@code lossDate} optional; may be {@code null} for no body)
     * @param ifMatch    raw {@code If-Match} header value (may be null)
     * @param clientDate civil "today" ({@code X-Client-Date} or UTC fallback) - used for the
     *                   {@code lossDate} upper-bound sanity check and the response snapshot
     * @return the updated/current profile as an "ended"-lifecycle response snapshot
     * @throws ApiException          404, 409 (lifecycle/state), 403, 422
     * @throws StaleVersionException 409 (version mismatch; caller attaches current profile body)
     */
    @Transactional
    public PregnancyProfileResponse recordLossEvent(UUID userId, LossEventInput input,
                                                    String ifMatch, LocalDate clientDate) {

        // 1 — Consent gate (general_health) — 403-BEFORE-404 per functional-spec §3: checked
        // before profile existence so a consent-withdrawn caller gets a consistent 403
        // regardless of whether a profile exists (avoids leaking profile existence via the
        // 403-vs-404 status distinction). (birth-event still checks profile-existence first —
        // a pre-existing divergence tracked separately for system-analyst to rule on; not
        // changed here, see recordBirthEvent.)
        if (!consentChecker.isGranted(userId, GENERAL_HEALTH)) {
            throw new ApiException(403, "consent_required", GENERAL_HEALTH);
        }

        // 2 — Profile must exist
        PregnancyProfile profile = requireProfile(userId);

        // 3 — If-Match required + freshness
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(428, "precondition_required");
        }
        long clientVersion = parseIfMatch(ifMatch);
        if (clientVersion != profile.getVersion()) {
            throw new StaleVersionException(buildResponse(profile, clientDate));
        }

        // 4 — Lifecycle guard (P7 truth table, §7.1)
        if ("postpartum".equals(profile.getLifecycle())) {
            throw new ApiException(409, "invalid_lifecycle_state", "postpartum");
        }

        // 5 — Validate lossDate (§7.2) — runs whether pregnant (apply) or already-ended (idempotent)
        LocalDate lossDate = parseAndValidateLossDate(input, profile.getEdd(), clientDate);

        boolean alreadyEnded = "ended".equals(profile.getLifecycle());
        if (alreadyEnded) {
            // §7.6 content-level idempotency: same lossDate (both-NULL counts as equal) → true no-op.
            if (java.util.Objects.equals(lossDate, profile.getLossDate())) {
                return buildResponse(profile, clientDate);
            }
            // Differing lossDate → correction only; sweep NOT re-run (already swept).
            profile.setLossDate(lossDate);
            PregnancyProfile saved = repository.saveAndFlush(profile);
            return buildResponse(saved, clientDate);
        }

        // 6 — pregnant → ended transition (the primary path) — LOSS-INV-3 atomic block
        profile.setLifecycle("ended");
        profile.setLossDate(lossDate);
        // birthDate/edd deliberately untouched (§7.3 steps 5).
        PregnancyProfile saved = repository.saveAndFlush(profile);

        // Reminder sweep — same transaction, same method, real DB UPDATE (LOSS-INV-3/4/5).
        reminderRepository.sweepDeactivateOnLossEvent(userId, Instant.now());

        return buildResponse(saved, clientDate);
    }

    // -------------------------------------------------------------------------
    // POST /pregnancy-profile/reopen
    // -------------------------------------------------------------------------

    /**
     * Reverses a pregnancy-loss event, transitioning {@code lifecycle: ended -> pregnant}
     * (functional-spec §7.4, US-4 / OQ-PP1). Explicit, always-available — NOT a timed undo.
     *
     * <p>Preconditions, in order:
     * <ol>
     *   <li>Profile must exist - {@code 404 not_found} if absent.</li>
     *   <li>Consent gate: {@code general_health} - {@code 403 consent_required}.</li>
     *   <li>{@code If-Match} - absent -&gt; {@code 428}; unparseable -&gt; {@code 412};
     *       stale -&gt; {@code 409} with the current profile.</li>
     *   <li>Lifecycle guard: {@code postpartum} -&gt; {@code 409 invalid_lifecycle_state
     *       details:"postpartum"}; {@code pregnant} -&gt; idempotent no-op; {@code ended} -&gt; apply.</li>
     * </ol>
     *
     * <p><strong>LOSS-INV-6 (S4)</strong>: {@code loss_date := NULL} is set in the SAME
     * transaction as the enum flip — a first-class transactional postcondition, never a
     * follow-up cleanup call. <strong>LOSS-INV-3</strong>: the enum flip, date-clear,
     * {@code version}/{@code updated_at} bump, AND the reminder re-activation sweep (every
     * {@code deactivated_by='loss_event' AND deleted_at IS NULL} reminder for this user) all
     * happen inside this one {@code @Transactional} method against the real store.
     *
     * <p><strong>Idempotency</strong>: already-{@code pregnant} -&gt; {@code 200}, no
     * {@code version} bump, no re-activation sweep re-run (functional-spec §7.6).
     *
     * @param userId     authenticated user id
     * @param ifMatch    raw {@code If-Match} header value (may be null)
     * @param clientDate civil "today" ({@code X-Client-Date} or UTC fallback) for the response
     * @return the updated/current profile as a "pregnant"-lifecycle response snapshot
     * @throws ApiException          404, 409 (lifecycle/state), 403
     * @throws StaleVersionException 409 (version mismatch; caller attaches current profile body)
     */
    @Transactional
    public PregnancyProfileResponse reopen(UUID userId, String ifMatch, LocalDate clientDate) {

        // 1 — Consent gate (general_health) — 403-BEFORE-404 per functional-spec §3 (same
        // ordering rationale as recordLossEvent; birth-event divergence tracked separately).
        if (!consentChecker.isGranted(userId, GENERAL_HEALTH)) {
            throw new ApiException(403, "consent_required", GENERAL_HEALTH);
        }

        // 2 — Profile must exist
        PregnancyProfile profile = requireProfile(userId);

        // 3 — If-Match required + freshness
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(428, "precondition_required");
        }
        long clientVersion = parseIfMatch(ifMatch);
        if (clientVersion != profile.getVersion()) {
            throw new StaleVersionException(buildResponse(profile, clientDate));
        }

        // 4 — Lifecycle guard (P7 truth table, §7.4)
        if ("postpartum".equals(profile.getLifecycle())) {
            throw new ApiException(409, "invalid_lifecycle_state", "postpartum");
        }
        if ("pregnant".equals(profile.getLifecycle())) {
            // Already pregnant → idempotent no-op: no bump, no re-activation sweep re-run.
            return buildResponse(profile, clientDate);
        }

        // 5 — ended → pregnant transition — LOSS-INV-3/6 atomic block
        profile.setLifecycle("pregnant");
        profile.setLossDate(null); // S4 — cleared, not orphaned, same transaction (LOSS-INV-6)
        // edd retained → gestational week resumes (§7.4 step 5).
        PregnancyProfile saved = repository.saveAndFlush(profile);

        // Reminder re-activation sweep — same transaction, real DB UPDATE (LOSS-INV-3/4/5).
        reminderRepository.sweepReactivateOnReopen(userId, Instant.now());

        return buildResponse(saved, clientDate);
    }

    /**
     * Parses and validates the {@code lossDate} field of a {@link LossEventInput}
     * (functional-spec §7.2). Runs REGARDLESS of whether the transition applies or is
     * idempotent, so a stale/malformed re-POST is still rejected consistently.
     *
     * <ul>
     *   <li>Absent input, absent key, or explicit null -&gt; returns {@code null} (full success,
     *       LOSS-INV-11 — never mandatory).</li>
     *   <li>Present but not a bare {@code YYYY-MM-DD} civil date (time component, impossible
     *       date, free text) -&gt; {@code 422 validation_error details:"loss_date_malformed"}.</li>
     *   <li>Present, parseable, but outside the light sanity window
     *       ({@code lossDate > clientDate+1d} OR {@code lossDate < edd-301d}) -&gt;
     *       {@code 422 validation_error details:"loss_date_range"} (same sub-code for both
     *       bounds, by design — §7.2).</li>
     * </ul>
     *
     * <p>The upper bound uses {@code clientDate + 1 day} slack per functional-spec §7.2 ("If
     * {@code X-Client-Date} is absent, fall back to server UTC date plus one calendar day of
     * slack") — applied uniformly here since {@code clientDate} is already resolved to that
     * UTC-fallback-or-header value by the controller before this method is called.
     */
    private static LocalDate parseAndValidateLossDate(LossEventInput input, LocalDate edd,
                                                      LocalDate clientDate) {
        String raw = (input != null) ? input.getLossDate() : null;
        if (raw == null || raw.isBlank()) {
            return null; // absent/omitted/explicit-null → stored NULL, full success
        }

        LocalDate lossDate;
        try {
            // Strict YYYY-MM-DD only — a time component (e.g. "...T12:00:00") or any
            // non-ISO-date shape fails LocalDate.parse and is rejected as malformed (§10.12).
            lossDate = LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(422, "validation_error", "loss_date_malformed");
        }

        LocalDate upperBound = clientDate.plusDays(1); // §7.2 slack for TH (UTC+7) post-midnight
        if (lossDate.isAfter(upperBound)) {
            throw new ApiException(422, "validation_error", "loss_date_range");
        }
        LocalDate lowerBound = edd.minusDays(LOSS_DATE_FLOOR_DAYS_BEFORE_EDD);
        if (lossDate.isBefore(lowerBound)) {
            throw new ApiException(422, "validation_error", "loss_date_range");
        }

        return lossDate;
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
     * Applies optional birth-event fields to the profile entity.
     *
     * <p>Called on both the initial {@code pregnant → postpartum} transition and on
     * correction (different birthDate) or hospital-key-present re-POSTs.
     *
     * <p>{@code deliveryType} and {@code birthNote}: only written when the request carries a
     * non-null value (simple nullable semantics — no "clear" operation for these two fields).
     *
     * <p>{@code hospitalAdmissionDate} / {@code hospitalDischargeDate}: three-way
     * absent/explicit-null/value semantics (contract L227):
     * <ul>
     *   <li>Absent (Java {@code null}) → leave column unchanged.</li>
     *   <li>{@code Optional.empty()} → set column to {@code null} (clear).</li>
     *   <li>{@code Optional.of(base64)} → Base64-decode and store as {@code bytea}.</li>
     * </ul>
     * Byte-cap validation for hospital fields has already been performed before this method
     * is called (fail-fast guard in {@link #recordBirthEvent}).
     *
     * <p>TODO security-compliance: replace deliveryType/birthNote with encrypted-bytea
     * when field-level encryption ships
     * (see {@link com.momstarter.pregnancy.dto.BirthEventInput} TODO block).
     */
    private static void applyOptionalFields(PregnancyProfile profile, BirthEventInput input) {
        if (input == null) return;
        if (input.getDeliveryType() != null) {
            profile.setDeliveryType(input.getDeliveryType());
        }
        if (input.getBirthNote() != null) {
            profile.setBirthNote(input.getBirthNote());
        }
        // Hospital-stay cipher fields: three-way absent/null/value semantics
        applyNameCipher(profile::setHospitalAdmissionDateCipher, input.getHospitalAdmissionDate());
        applyNameCipher(profile::setHospitalDischargeDateCipher, input.getHospitalDischargeDate());
    }

    /**
     * Validates that a hospital-date cipher field, when present and non-null, decodes to at most
     * {@value #MAX_HOSPITAL_DATE_CIPHER_BYTES} bytes.
     * Throws 422 {@code validation_error} with details {@code hospital_date_too_large} when exceeded.
     *
     * <p>The server never parses or temporally validates the date bytes — temporal logic is
     * client-side only (pregnancy-summary-design.md §1.3). This guard is purely a payload
     * size bound (mirrors {@link #validateNameCipherSize}).
     *
     * <p>Absent fields (Java {@code null}) and explicit nulls ({@code Optional.empty()}) are
     * skipped — there are no bytes to validate.
     *
     * @param dateField the hospital-date Optional from the DTO
     * @throws com.momstarter.error.ApiException 422 validation_error (details: hospital_date_too_large)
     *         if the decoded ciphertext exceeds {@value #MAX_HOSPITAL_DATE_CIPHER_BYTES} bytes,
     *         or 422 validation_error (no details) if not valid Base64
     */
    private static void validateHospitalDateCipherSize(Optional<String> dateField) {
        if (dateField == null || !dateField.isPresent()) {
            return; // absent or explicit null — no bytes to validate
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(dateField.get());
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, "validation_error");
        }
        if (decoded.length > MAX_HOSPITAL_DATE_CIPHER_BYTES) {
            throw new ApiException(422, "validation_error", "hospital_date_too_large");
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
