package com.momstarter.dev;

import com.momstarter.auth.PasswordEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * LOCAL-ONLY dev email sender for the password-reset flow (BE-DEV-1/2).
 *
 * <p>Active only when {@code momstarter.dev.expose-reset-token=true}. When active it is
 * {@link Primary} and therefore overrides {@link com.momstarter.auth.LoggingPasswordEmailSender}.
 * It logs the <em>full reset deep-link including the raw token</em> at {@code WARN} level so a
 * local UAT tester can copy it without a real email inbox.
 *
 * <p><strong>Security invariants (enforced by {@link ResetTokenExposureGuard}):</strong>
 * <ol>
 *   <li>This bean is never created when the flag is absent/false → prod uses the safe stub.</li>
 *   <li>If the flag is true on a non-local env, {@link ResetTokenExposureGuard} throws at
 *       startup → this sender never receives any call.</li>
 *   <li>Log output goes to the local console only; the {@code local} profile must not attach
 *       a remote/central log appender (BE-DEV-6).</li>
 * </ol>
 *
 * <p><strong>NEVER enable in staging or production config.</strong>
 */
@Component
@Primary
@ConditionalOnProperty(name = "momstarter.dev.expose-reset-token", havingValue = "true")
public class LocalDevPasswordEmailSender implements PasswordEmailSender {

    private static final Logger log = LoggerFactory.getLogger(LocalDevPasswordEmailSender.class);

    @Override
    public void sendPasswordReset(String email, String rawToken) {
        log.warn("DEV RESET LINK (LOCAL ONLY): momstarter://reset-password?token={}   (email={})",
                rawToken, email);
    }

    @Override
    public void sendPasswordChangedNotice(String email) {
        log.info("[DEV] Password changed for {} (LOCAL ONLY)", email);
    }
}
