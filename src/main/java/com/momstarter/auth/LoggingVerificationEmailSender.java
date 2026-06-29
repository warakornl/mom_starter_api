package com.momstarter.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder sender until real transactional email (SES) is wired. It deliberately does NOT
 * log the raw token (which would leak it); it only records that a message would be sent.
 */
@Component
public class LoggingVerificationEmailSender implements VerificationEmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationEmailSender.class);

    @Override
    public void sendVerification(String email, String rawToken) {
        log.info("Would send verification email to {} (token withheld from logs)", email);
    }

    @Override
    public void sendAlreadyRegisteredNotice(String email) {
        log.info("Would send 'already registered' notice to {}", email);
    }
}
