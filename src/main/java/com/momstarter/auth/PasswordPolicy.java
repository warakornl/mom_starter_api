package com.momstarter.auth;

import com.momstarter.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * New-password validation (auth §F / appsec §2): a length floor plus a breached-password
 * check. Both describe the SUBMITTED password (not account existence), so returning a
 * specific 422 code here is safe and non-enumerating.
 */
@Component
public class PasswordPolicy {

    static final int MIN_LENGTH = 8;

    private final BreachedPasswordChecker breachedChecker;

    public PasswordPolicy(BreachedPasswordChecker breachedChecker) {
        this.breachedChecker = breachedChecker;
    }

    public void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new ApiException(422, "password_too_short");
        }
        if (breachedChecker.isBreached(rawPassword)) {
            throw new ApiException(422, "password_breached");
        }
    }
}
