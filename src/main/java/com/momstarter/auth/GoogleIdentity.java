package com.momstarter.auth;

/**
 * The verified claims extracted from a Google ID token (§J). {@code sub} is Google's stable
 * subject identifier for the user.
 */
public record GoogleIdentity(String sub, String email, boolean emailVerified) {
}
