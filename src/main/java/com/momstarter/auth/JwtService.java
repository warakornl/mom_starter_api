package com.momstarter.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues short-lived RS256 access tokens (§A/§F). The payload carries ONLY
 * {@code sub}, {@code email_verified}, {@code iat}, {@code exp} — never email or
 * other PII. Verification is done by the alg-pinned {@code JwtDecoder} (JwtKeyConfig).
 */
@Service
public class JwtService {

    static final Duration ACCESS_TTL = Duration.ofMinutes(15);

    private final RSAKey rsaKey;
    private final JWSSigner signer;

    public JwtService(RSAKey rsaKey) throws JOSEException {
        this.rsaKey = rsaKey;
        this.signer = new RSASSASigner(rsaKey);
    }

    public String issueAccessToken(UUID userId, boolean emailVerified) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("email_verified", emailVerified)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ACCESS_TTL)))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign access token", e);
        }
        return jwt.serialize();
    }

    public long accessTtlSeconds() {
        return ACCESS_TTL.toSeconds();
    }
}
