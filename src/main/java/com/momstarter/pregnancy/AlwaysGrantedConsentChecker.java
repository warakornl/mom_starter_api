package com.momstarter.pregnancy;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stub implementation of {@link ConsentChecker} that always returns {@code true} (granted).
 *
 * <p><strong>Profile strategy (consent-slice-design.md §4.4):</strong>
 * This bean is active <em>only</em> in the {@code test} Spring profile via
 * {@code @Profile("test")}.  In production and UAT (default profile) the
 * {@link com.momstarter.consent.ConsentRecordConsentChecker} ({@code @Profile("!test")},
 * {@code @Primary}) is loaded instead.
 *
 * <p><strong>Phase-2 gate:</strong> activating the real checker in the production environment
 * (flipping the default config) is a separate step gated on the mobile consent flow.  Until
 * that flip, the deployed application uses this stub so that UAT is unblocked.  The gate code
 * path in {@link PregnancyProfileService} is already wired and will enforce automatically once
 * the flip is made — no call-site changes are required.
 *
 * <p>PDPA note: granting consent unconditionally here is intentional for the MVP UAT phase.
 * The gate is <strong>not</strong> permanently bypassed — it is wired and will enforce once
 * the real implementation is activated.
 */
@Component
@Profile("test")
public class AlwaysGrantedConsentChecker implements ConsentChecker {

    @Override
    public boolean isGranted(UUID userId, String consentType) {
        // TODO: wire real consent check — query ConsentRecord where user_id=userId
        //       and consent_type=consentType, ordered by granted_at DESC, check granted=true
        return true;
    }
}
