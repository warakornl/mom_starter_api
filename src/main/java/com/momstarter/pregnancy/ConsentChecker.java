package com.momstarter.pregnancy;

import java.util.UUID;

/**
 * Seam for PDPA consent gate checks.
 *
 * <p>The api-contract mandates that {@code PUT /pregnancy-profile} (and
 * {@code POST /pregnancy-profile/birth-event}) return {@code 403 consent_required}
 * (with {@code details: "general_health"}) when the user has not granted
 * {@code general_health} consent (api-contract "Consent gating — health-data processing").
 *
 * <p>The {@code account/consents} feature (POST /account/consents, ConsentRecord) is not yet
 * implemented. This interface is a <em>seam</em> so the endpoint works end-to-end now and
 * the real gate can be wired once the consents slice ships, without changing callers.
 *
 * <p>The live implementation ({@link AlwaysGrantedConsentChecker}) always returns {@code true}
 * so UAT is unblocked. Replace it with a {@code ConsentRepository}-backed bean when
 * {@code account/consents} is built.
 *
 * @see AlwaysGrantedConsentChecker
 */
@FunctionalInterface
public interface ConsentChecker {

    /**
     * Returns {@code true} if the user currently holds an active (latest row = granted)
     * consent record for the given {@code consentType}.
     *
     * @param userId      the authenticated user id
     * @param consentType e.g. {@code "general_health"}, {@code "cloud_storage"}
     */
    boolean isGranted(UUID userId, String consentType);
}
