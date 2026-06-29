package com.momstarter.auth;

/**
 * Sends verification-related email out-of-band (§E/§G). Real SES delivery is a later slice;
 * the default impl logs. Two messages: the verification link for a genuinely new account, and
 * the "someone tried to register with your email" notice for a collision (so register can stay
 * strictly non-enumerating — the HTTP response is identical either way).
 */
public interface VerificationEmailSender {

    void sendVerification(String email, String rawToken);

    void sendAlreadyRegisteredNotice(String email);
}
