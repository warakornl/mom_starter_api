package com.momstarter.auth;

import com.momstarter.config.JwtKeyConfig;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private RSAKey rsaKey;
    private JwtService jwtService;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        JwtKeyConfig keyConfig = new JwtKeyConfig();
        rsaKey = keyConfig.rsaKey();
        jwtService = new JwtService(rsaKey);
        decoder = keyConfig.jwtDecoder(rsaKey);
    }

    @Test
    void issuesRs256TokenWithSubjectAndEmailVerified_andNoPii() {
        UUID userId = UUID.randomUUID();

        String token = jwtService.issueAccessToken(userId, true);
        Jwt decoded = decoder.decode(token);

        assertThat(decoded.getSubject()).isEqualTo(userId.toString());
        assertThat(decoded.getClaimAsBoolean("email_verified")).isTrue();
        assertThat(decoded.getExpiresAt()).isAfter(Instant.now());
        assertThat(decoded.getHeaders()).containsEntry("alg", "RS256");
        // no personal data in the payload
        assertThat(decoded.getClaims()).doesNotContainKeys("email", "name", "locale");
    }

    @Test
    void carriesEmailVerifiedFalseForUnverifiedSession() {
        String token = jwtService.issueAccessToken(UUID.randomUUID(), false);
        assertThat(decoder.decode(token).getClaimAsBoolean("email_verified")).isFalse();
    }

    @Test
    void rejectsTokenSignedWithDifferentAlgorithm() throws Exception {
        // forge an HS256 token — the RS256-pinned decoder must reject it (alg pinning / "alg=none" class of attack)
        SignedJWT hs = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).build());
        hs.sign(new MACSigner("0123456789-0123456789-0123456789")); // 32-byte secret
        String forged = hs.serialize();

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }
}
