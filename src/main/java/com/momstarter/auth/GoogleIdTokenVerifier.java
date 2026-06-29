package com.momstarter.auth;

/**
 * Strictly verifies a Google ID token (signature against Google's JWKS, issuer, audience,
 * expiry, and nonce — §J/G2) and returns the verified {@link GoogleIdentity}, throwing
 * {@code ApiException(401, "google_token_invalid")} on any failure.
 *
 * <p>The real implementation (Google JWKS verification) is a later slice; tests inject a fake.
 */
public interface GoogleIdTokenVerifier {

    GoogleIdentity verify(String idToken, String nonce);
}
