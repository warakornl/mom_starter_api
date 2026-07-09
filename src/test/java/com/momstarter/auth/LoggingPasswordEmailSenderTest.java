package com.momstarter.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-9 — Flag unset: LoggingPasswordEmailSender (the default stub) must NOT log the raw
 * reset token in any log line. The token is a password-change capability; logging it
 * would expose it to any log sink (CloudWatch, Loki, etc.) even without the dev flag.
 *
 * <p>These tests capture Logback log output directly and assert that neither the raw
 * token string nor the deep-link pattern "reset-password?token=" appear in any log line
 * produced by sendPasswordReset() (BE-CORE-8 / appsec T-9).
 */
class LoggingPasswordEmailSenderTest {

    private static final String RAW_TOKEN = "testRawToken_ABCDEFGH1234567890";

    private ListAppender<ILoggingEvent> listAppender;
    private Logger senderLogger;

    @BeforeEach
    void attachAppender() {
        senderLogger = (Logger) LoggerFactory.getLogger(LoggingPasswordEmailSender.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        senderLogger.addAppender(listAppender);
        senderLogger.setLevel(Level.ALL);
    }

    @AfterEach
    void detachAppender() {
        senderLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    /**
     * T-9: sendPasswordReset logs nothing containing the raw token or the deep-link pattern.
     * The only acceptable log is a safe "email X (token withheld)" line.
     */
    @Test
    void sendPasswordReset_doesNotLogRawToken(  ) {
        new LoggingPasswordEmailSender().sendPasswordReset("mom@example.com", RAW_TOKEN);

        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).isNotEmpty(); // a safe log line must exist (to prove the method ran)

        for (ILoggingEvent event : events) {
            String formatted = event.getFormattedMessage();
            assertThat(formatted)
                    .as("Log line must not contain the raw reset token (T-9 / BE-CORE-8): [%s]", formatted)
                    .doesNotContain(RAW_TOKEN);
            assertThat(formatted)
                    .as("Log line must not contain the deep-link pattern with token (T-9): [%s]", formatted)
                    .doesNotContain("reset-password?token=");
        }
    }

    /**
     * T-9b: sendPasswordChangedNotice (the "password was changed" email stub) must not log
     * any reset token or deep-link material. The word "password" in the descriptive message
     * "password changed" is acceptable; only actual capability material is banned.
     */
    @Test
    void sendPasswordChangedNotice_doesNotLogTokenOrDeepLink() {
        new LoggingPasswordEmailSender().sendPasswordChangedNotice("mom@example.com");

        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).isNotEmpty();

        for (ILoggingEvent event : events) {
            String formatted = event.getFormattedMessage();
            // The notice must not contain a reset token value or deep-link pattern.
            // The word "password" alone is fine (descriptive "password changed" notice).
            assertThat(formatted)
                    .as("Changed-notice log must not contain token material (T-9b): [%s]", formatted)
                    .doesNotContain("reset-password?token=")
                    .doesNotContain(RAW_TOKEN);
        }
    }
}
