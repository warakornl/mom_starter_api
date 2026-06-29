package com.momstarter.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder sender until real transactional email (SES) is wired. It deliberately does NOT
 * log the raw token (which would leak it).
 */
@Component
public class LoggingPasswordEmailSender implements PasswordEmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordEmailSender.class);

    @Override
    public void sendPasswordReset(String email, String rawToken) {
        log.info("Would send password-reset email to {} (token withheld from logs)", email);
    }

    @Override
    public void sendPasswordChangedNotice(String email) {
        log.info("Would send 'password changed' notice to {}", email);
    }
}
