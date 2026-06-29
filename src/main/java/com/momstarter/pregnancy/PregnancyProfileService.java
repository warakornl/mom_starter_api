package com.momstarter.pregnancy;

import com.momstarter.error.ApiException;
import com.momstarter.pregnancy.dto.PregnancyProfileInput;
import com.momstarter.pregnancy.dto.PregnancyProfileResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for GET/PUT /pregnancy-profile (api-contract "Pregnancy Profile" section,
 * data-model §3.1, OQ-4/5/6/7/9/16).
 *
 * <p>Validates input, checks the consent gate (via the {@link ConsentChecker} seam — currently
 * always-granted pending the account/consents feature), applies the EDD window guard, and enforces
 * optimistic concurrency via the {@code If-Match} header.
 *
 * <p>This phase is pregnant-lifecycle only. The birth-event phase (OQ-8/10/11/12/13) is deferred.
 */
@Service
public class PregnancyProfileService {

    private static final String GENERAL_HEALTH = "general_health";

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
     * Returns the live (non-deleted) profile for the user, with a derived gestational-age
     * snapshot computed from {@code clientDate}.
     *
     * @param userId     authenticated user id (JWT subject)
     * @param clientDate the civil "today" for the snapshot ({@code X-Client-Date} or UTC fallback)
     * @return the response DTO
     * @throws ApiException 404 {@code not_found} when no profile exists yet (OQ-4)
     */
    @Transactional(readOnly = true)
    public PregnancyProfileResponse get(UUID userId, LocalDate clientDate) {
        PregnancyProfile profile = requireProfile(userId);
        GestationalAge ga = GestationalAge.compute(profile.getEdd(), clientDate, profile.getLifecycle());
        return PregnancyProfileResponse.of(profile, ga);
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
     *   <li>Consent gate: {@code general_health} must be granted (403 if not). Currently always
     *       granted via the {@link AlwaysGrantedConsentChecker} seam.</li>
     *   <li>{@code currentWeek → edd} back-computation: {@code edd = clientDate + (280 − N*7)}.</li>
     *   <li>EDD plausibility window: {@code clientDate−28d ≤ edd ≤ clientDate+308d} → 422.</li>
     *   <li>Create (no existing row): persist new profile → return Created (HTTP 201). No
     *       {@code If-Match} required for a create.</li>
     *   <li>Update (row exists):
     *       <ul>
     *         <li>{@code If-Match} header absent → 428 Precondition Required.</li>
     *         <li>Version mismatch → throw {@link StaleVersionException} with current profile
     *             (caller returns 409 with current body).</li>
     *         <li>Version matches, EDD unchanged → no-op, return Updated (HTTP 200, version NOT bumped).</li>
     *         <li>Version matches, EDD changed → persist update, return Updated (HTTP 200).</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @param userId     authenticated user id
     * @param input      request body
     * @param ifMatch    raw {@code If-Match} header value (may be null)
     * @param clientDate civil "today" ({@code X-Client-Date} or UTC fallback)
     * @return {@link PutResult.Created} or {@link PutResult.Updated}
     * @throws ApiException         422 validation_error (XOR, EDD window), 403 consent_required
     * @throws ApiException         428 precondition_required (If-Match absent on update)
     * @throws StaleVersionException 409 (version mismatch; caller attaches current profile body)
     */
    @Transactional
    public PutResult put(UUID userId, PregnancyProfileInput input,
                         String ifMatch, LocalDate clientDate) {

        // 1 — XOR validation (both present or both absent → 422)
        boolean hasEdd         = input != null && input.edd() != null;
        boolean hasCurrentWeek = input != null && input.currentWeek() != null;
        if (hasEdd == hasCurrentWeek) {
            throw new ApiException(422, "validation_error");
        }

        // 2 — Consent gate (general_health). Currently always-granted via seam.
        //     TODO: the real ConsentRecord check is wired here; only the stub changes.
        if (!consentChecker.isGranted(userId, GENERAL_HEALTH)) {
            throw new ApiException(403, "consent_required");
        }

        // 3 — Resolve EDD and eddBasis
        LocalDate edd;
        String eddBasis;
        if (hasEdd) {
            edd      = input.edd();
            eddBasis = "due_date";
        } else {
            int week = input.currentWeek();
            edd      = clientDate.plusDays(280L - (long) week * 7);
            eddBasis = "current_week";
        }

        // 4 — EDD plausibility window: clientDate−28d ≤ edd ≤ clientDate+308d (OQ-6)
        LocalDate minEdd = clientDate.minusDays(28);
        LocalDate maxEdd = clientDate.plusDays(308);
        if (edd.isBefore(minEdd) || edd.isAfter(maxEdd)) {
            throw new ApiException(422, "validation_error");
        }

        // 5 — Look up existing live profile
        Optional<PregnancyProfile> existing = repository.findByUserIdAndDeletedAtIsNull(userId);

        if (existing.isEmpty()) {
            // 5a — Create: no If-Match required (OQ-5)
            PregnancyProfile profile = new PregnancyProfile();
            profile.setUserId(userId);
            profile.setEdd(edd);
            profile.setEddBasis(eddBasis);
            // saveAndFlush: forces the INSERT + @Version assignment immediately so that the
            // response includes the correct version=0. Without flush, Hibernate might not
            // have executed the INSERT yet at response-building time (in @Transactional tests).
            PregnancyProfile saved = repository.saveAndFlush(profile);
            GestationalAge ga = GestationalAge.compute(saved.getEdd(), clientDate, saved.getLifecycle());
            return new PutResult.Created(PregnancyProfileResponse.of(saved, ga));
        }

        // 5b — Update: If-Match is MANDATORY (OQ-5)
        PregnancyProfile profile = existing.get();
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(428, "precondition_required");
        }

        long clientVersion = parseIfMatch(ifMatch);
        if (clientVersion != profile.getVersion()) {
            GestationalAge ga = GestationalAge.compute(profile.getEdd(), clientDate, profile.getLifecycle());
            throw new StaleVersionException(PregnancyProfileResponse.of(profile, ga));
        }

        // No-op (OQ-9): same EDD → return unchanged record without bumping version
        if (edd.equals(profile.getEdd())) {
            GestationalAge ga = GestationalAge.compute(profile.getEdd(), clientDate, profile.getLifecycle());
            return new PutResult.Updated(PregnancyProfileResponse.of(profile, ga));
        }

        // Real update: saveAndFlush to ensure the @Version is incremented in-memory before the
        // response is built. Without flush the managed entity's version would still read 0
        // even though the SQL UPDATE has queued version+1.
        profile.setEdd(edd);
        profile.setEddBasis(eddBasis);
        PregnancyProfile saved = repository.saveAndFlush(profile);
        GestationalAge ga = GestationalAge.compute(saved.getEdd(), clientDate, saved.getLifecycle());
        return new PutResult.Updated(PregnancyProfileResponse.of(saved, ga));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PregnancyProfile requireProfile(UUID userId) {
        return repository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ApiException(404, "not_found"));
    }

    /**
     * Parses the raw {@code If-Match} header value. HTTP ETag convention wraps the value in
     * double quotes (e.g. {@code "0"}), so we strip them before parsing.
     */
    private static long parseIfMatch(String ifMatch) {
        try {
            return Long.parseLong(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new ApiException(428, "precondition_required");
        }
    }
}
