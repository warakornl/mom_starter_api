package com.momstarter.auth;

/**
 * Checks a candidate password against a breached-password corpus (appsec §2).
 * The real implementation (HIBP k-anonymity) is a later slice; for now a no-op stub.
 */
public interface BreachedPasswordChecker {

    boolean isBreached(String rawPassword);
}
