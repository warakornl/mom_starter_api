package com.momstarter.auth;

import com.momstarter.error.ApiException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real Google ID-token verifier (§J/G2). Tests act as "Google": a local RSA key signs tokens
 * and is published as the JWKS the verifier trusts — so we exercise signature / issuer / audience /
 * expiry / nonce checks without hitting Google.
 */
class GoogleJwksIdTokenVerifierTest {

    private static final String CLIENT_ID = "test-client.apps.googleusercontent.com";

    private RSAKey googleKey;
    private JWKSource<SecurityContext> jwkSource;
    private GoogleJwksIdTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        googleKey = new RSAKeyGenerator(2048).keyID("g-key-1").generate();
        jwkSource = new ImmutableJWKSet<>(new JWKSet(googleKey.toPublicJWK()));
        verifier = new GoogleJwksIdTokenVerifier(CLIENT_ID, jwkSource);
    }

    private JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(CLIENT_ID)
                .subject("google-sub-123")
                .claim("email", "mom@example.com")
                .claim("email_verified", true)
                .claim("nonce", "the-nonce")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    }

    private String signWith(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void validToken_returnsIdentity() throws Exception {
        GoogleIdentity id = verifier.verify(signWith(googleKey, validClaims().build()), "the-nonce");

        assertThat(id.sub()).isEqualTo("google-sub-123");
        assertThat(id.email()).isEqualTo("mom@example.com");
        assertThat(id.emailVerified()).isTrue();
    }

    @Test
    void wrongAudience_throws401() throws Exception {
        String token = signWith(googleKey, validClaims().audience("someone-else").build());
        assertThatThrownBy(() -> verifier.verify(token, "the-nonce"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("google_token_invalid");
    }

    @Test
    void expiredToken_throws401() throws Exception {
        String token = signWith(googleKey, validClaims().expirationTime(Date.from(Instant.now().minusSeconds(60))).build());
        assertThatThrownBy(() -> verifier.verify(token, "the-nonce"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("google_token_invalid");
    }

    @Test
    void wrongNonce_throws401() throws Exception {
        String token = signWith(googleKey, validClaims().build());
        assertThatThrownBy(() -> verifier.verify(token, "different-nonce"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("google_token_invalid");
    }

    @Test
    void wrongSignature_throws401() throws Exception {
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("g-key-1").generate(); // same kid, different key
        String token = signWith(attackerKey, validClaims().build());
        assertThatThrownBy(() -> verifier.verify(token, "the-nonce"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("google_token_invalid");
    }

    @Test
    void wrongIssuer_throws401() throws Exception {
        String token = signWith(googleKey, validClaims().issuer("https://evil.example.com").build());
        assertThatThrownBy(() -> verifier.verify(token, "the-nonce"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("google_token_invalid");
    }

    @Test
    void unconfiguredClientId_rejectsEverything() throws Exception {
        GoogleJwksIdTokenVerifier disabled = new GoogleJwksIdTokenVerifier("", jwkSource);
        String token = signWith(googleKey, validClaims().build());
        assertThatThrownBy(() -> disabled.verify(token, "the-nonce"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("google_token_invalid");
    }
}
