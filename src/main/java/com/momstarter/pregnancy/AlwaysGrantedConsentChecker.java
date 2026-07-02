package com.momstarter.pregnancy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stub implementation of {@link ConsentChecker} that always returns {@code true} (granted).
 *
 * <p><strong>Gating strategy (consent-slice-design.md §4.4):</strong>
 * This bean is the DEFAULT checker — active whenever {@code momstarter.consent.enforce}
 * is absent or {@code false} ({@code @ConditionalOnProperty(..., matchIfMissing=true)}).
 * So production, UAT, and the {@code test} profile all use this stub UNTIL the flip. The
 * real {@link com.momstarter.consent.ConsentRecordConsentChecker} ({@code @Primary})
 * activates only when {@code momstarter.consent.enforce=true} is explicitly set.
 *
 * <p><strong>Phase-2 gate (the flip):</strong> set {@code momstarter.consent.enforce=true}
 * in the production config once the mobile consent flow is live. The gate code path in
 * {@link PregnancyProfileService} is already wired and enforces automatically once the real
 * checker is active — no call-site changes are required.
 *
 * <p>PDPA note: granting consent unconditionally here is intentional for the MVP UAT phase.
 * The gate is <strong>not</strong> permanently bypassed — it is wired and will enforce once
 * the real implementation is activated.
 */
@Component
@ConditionalOnProperty(name = "momstarter.consent.enforce", havingValue = "false", matchIfMissing = true)
public class AlwaysGrantedConsentChecker implements ConsentChecker {

    @Override
    public boolean isGranted(UUID userId, String consentType) {
        // TODO: wire real consent check — query ConsentRecord where user_id=userId
        //       and consent_type=consentType, ordered by granted_at DESC, check granted=true
        return true;
    }
}
