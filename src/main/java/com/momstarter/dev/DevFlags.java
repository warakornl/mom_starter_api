package com.momstarter.dev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single binding-layer holder for every {@code momstarter.dev.*} flag.
 *
 * <p><b>Why this exists (Pass 1 regression root cause):</b> {@code DevModeGuard},
 * {@code DevModeSeeder}, {@code LocalDevPasswordEmailSender}, and {@code ResetTokenExposureGuard}
 * are all {@code @Profile("!prod")}, so they cannot be constructed under the {@code prod}
 * profile — but that annotation only protects THOSE FOUR BEANS. Any other class that bound a
 * {@code momstarter.dev.*} property directly via {@code @Value} (as {@code RegistrationService}
 * used to) is a plain, unconditional bean that IS constructed under every profile including
 * {@code prod}, completely bypassing the firewall. That let a poisoned
 * {@code momstarter.dev.auto-verify-email=true} silently skip email verification in production.
 *
 * <p><b>The fix:</b> nobody should bind {@code momstarter.dev.*} via a raw {@code @Value} ever
 * again. Instead, every consumer takes an {@code Optional<DevFlags>} (this bean is
 * {@code @Profile("!prod")}, so it simply does not exist in a prod context) and treats an empty
 * Optional as "every dev flag is false." This makes the safety property hold BY CONSTRUCTION for
 * this consumer and any future one: there is no code path under {@code prod} where a
 * {@link DevFlags} instance can exist, so there is no code path where {@code isAutoVerifyEmail()}
 * can return {@code true} while running as {@code prod} — regardless of what the raw property is
 * (mis)configured to.
 *
 * <p>This mirrors, at the binding layer, the same {@code @Profile("!prod")} bean-creation-time
 * exclusion that already protects the four dev-only beans (see
 * {@code deploy-pipeline-and-cloud-options.md} §1.2 Path A) — it does not introduce a new pattern,
 * it closes the one gap where a consumer sat outside that pattern.
 */
@Component
@Profile("!prod")
public class DevFlags {

    private final boolean autoVerifyEmail;

    public DevFlags(@Value("${momstarter.dev.auto-verify-email:false}") boolean autoVerifyEmail) {
        this.autoVerifyEmail = autoVerifyEmail;
    }

    /**
     * DEV ONLY — true only when {@code momstarter.dev.auto-verify-email=true} AND the active
     * profile is not {@code prod} (this bean would not exist otherwise). Callers must obtain this
     * value through {@code Optional<DevFlags>} and treat an empty Optional as {@code false}.
     */
    public boolean isAutoVerifyEmail() {
        return autoVerifyEmail;
    }
}
