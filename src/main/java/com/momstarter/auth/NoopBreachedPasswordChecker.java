package com.momstarter.auth;

import org.springframework.stereotype.Component;

/**
 * Placeholder breach checker — always reports "not breached" until the real
 * HIBP-k-anonymity check is implemented (flagged OUT OF SCOPE in the auth plan).
 */
@Component
public class NoopBreachedPasswordChecker implements BreachedPasswordChecker {

    @Override
    public boolean isBreached(String rawPassword) {
        return false;
    }
}
