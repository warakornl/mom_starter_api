package com.momstarter.auth;

/**
 * Sends password-recovery email out-of-band (§5). Real SES delivery is a later slice; the
 * default impl logs. The reset link goes only to a known account; the "your password was
 * changed" notice confirms a completed reset to the owner.
 */
public interface PasswordEmailSender {

    void sendPasswordReset(String email, String rawToken);

    void sendPasswordChangedNotice(String email);
}
