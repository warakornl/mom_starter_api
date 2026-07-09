package com.momstarter.auth;

import com.momstarter.account.UserRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Async worker for the forgot-password email dispatch (BE-CORE-1 — constant-time path).
 *
 * <p>The HTTP handler calls {@link #dispatch(String)} <em>regardless</em> of whether the email
 * has an account. The HTTP thread returns immediately (enqueue only). This worker then runs on a
 * Spring {@code TaskExecutor} thread, looks up the account, and issues+sends a token only when
 * the account exists. The non-existent-account branch is a fast no-op on this background thread,
 * but that timing is invisible to the HTTP caller — the HTTP response latency is constant across
 * both branches.
 *
 * <p>The method must be in a SEPARATE bean from {@link PasswordRecoveryService} so that the
 * Spring AOP async proxy intercepts the call (self-invocation would bypass the proxy).
 */
@Service
public class ForgotPasswordWorker {

    private final UserRepository users;
    private final PasswordResetService passwordReset;
    private final PasswordEmailSender emailSender;

    public ForgotPasswordWorker(UserRepository users,
                                PasswordResetService passwordReset,
                                PasswordEmailSender emailSender) {
        this.users = users;
        this.passwordReset = passwordReset;
        this.emailSender = emailSender;
    }

    /**
     * Dispatched asynchronously. Looks up the account and sends the reset email only when
     * the account exists; otherwise silently completes (non-enumerating background path).
     *
     * @param normalisedEmail lower-cased, trimmed email address
     */
    @Async
    public void dispatch(String normalisedEmail) {
        users.findByEmail(normalisedEmail)
                .ifPresent(user ->
                        emailSender.sendPasswordReset(normalisedEmail, passwordReset.issue(user)));
    }
}
