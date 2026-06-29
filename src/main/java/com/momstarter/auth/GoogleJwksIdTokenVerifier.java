package com.momstarter.auth;

import com.momstarter.error.ApiException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Real Google ID-token verifier (§J/G2): validates the RS256 signature against Google's JWKS,
 * the audience (our client id), expiry, required claims, the issuer, and the nonce. Any failure →
 * one generic {@code 401 google_token_invalid}. When {@code google.client-id} is unset the
 * verifier rejects everything (Google sign-in disabled-but-safe until configured).
 */
@Component
public class GoogleJwksIdTokenVerifier implements GoogleIdTokenVerifier {

    private static final Set<String> GOOGLE_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;
    private final ConfigurableJWTProcessor<SecurityContext> processor;

    public GoogleJwksIdTokenVerifier(@Value("${google.client-id:}") String clientId,
                                     JWKSource<SecurityContext> googleJwkSource) {
        this.clientId = clientId;
        ConfigurableJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, googleJwkSource));
        // exact-match the audience; DefaultJWTClaimsVerifier also enforces exp; required claims must be present.
        p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder().audience(clientId).build(),
                Set.of("sub", "email", "exp", "iss")));
        this.processor = p;
    }

    @Override
    public GoogleIdentity verify(String idToken, String nonce) {
        if (clientId == null || clientId.isBlank()) {
            throw new ApiException(401, "google_token_invalid");
        }
        try {
            JWTClaimsSet claims = processor.process(idToken, null); // signature + aud + exp + required claims
            if (!GOOGLE_ISSUERS.contains(claims.getIssuer())) {
                throw new ApiException(401, "google_token_invalid");
            }
            if (nonce != null && !nonce.equals(claims.getClaim("nonce"))) {
                throw new ApiException(401, "google_token_invalid");
            }
            Object emailVerified = claims.getClaim("email_verified");
            boolean verified = Boolean.TRUE.equals(emailVerified) || "true".equals(String.valueOf(emailVerified));
            return new GoogleIdentity(claims.getSubject(), (String) claims.getClaim("email"), verified);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(401, "google_token_invalid");
        }
    }
}
