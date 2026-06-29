package com.momstarter.auth;

import com.momstarter.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Placeholder until real Google JWKS verification is wired. It rejects every token
 * ({@code 401 google_token_invalid}), so Google sign-in is effectively disabled — and never
 * insecurely accepted — until a real verifier replaces it.
 */
@Component
public class PlaceholderGoogleIdTokenVerifier implements GoogleIdTokenVerifier {

    @Override
    public GoogleIdentity verify(String idToken, String nonce) {
        throw new ApiException(401, "google_token_invalid");
    }
}
