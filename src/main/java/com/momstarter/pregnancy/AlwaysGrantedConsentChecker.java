package com.momstarter.pregnancy;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stub implementation of {@link ConsentChecker} that always returns {@code true} (granted).
 *
 * <p>TODO: Replace with a real DB-backed implementation once the {@code account/consents}
 * feature (POST /account/consents, ConsentRecord) is implemented. At that point, query the
 * latest {@code ConsentRecord} row for {@code (userId, consentType)} and return
 * {@code record.granted == true}.
 *
 * <p>This stub is the primary {@link ConsentChecker} bean for MVP. The gate code path in
 * {@link PregnancyProfileService} is already wired — only this stub needs to be swapped
 * for the real implementation when the consents slice ships.
 *
 * <p>PDPA note: the {@code general_health} gate on {@code PUT /pregnancy-profile} is a
 * server-side defense-in-depth check (api-contract "Consent gating — health-data processing").
 * Granting consent unconditionally here is intentional for the MVP UAT phase. The gate
 * is <strong>not</strong> permanently bypassed — it is wired and will enforce once the
 * real implementation is installed.
 */
@Component
public class AlwaysGrantedConsentChecker implements ConsentChecker {

    @Override
    public boolean isGranted(UUID userId, String consentType) {
        // TODO: wire real consent check — query ConsentRecord where user_id=userId
        //       and consent_type=consentType, ordered by granted_at DESC, check granted=true
        return true;
    }
}
