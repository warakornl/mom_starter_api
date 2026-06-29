package com.momstarter.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The RSA signing key for access tokens and the matching, ALG-PINNED decoder.
 *
 * <p>For now the keypair is generated at startup. In production it is loaded from
 * Secrets Manager / KMS (config-driven) — see docs/architecture/infrastructure-diagram.md;
 * the wiring here stays the same.
 */
@Configuration
public class JwtKeyConfig {

    @Bean
    public RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID("mom-starter-access-key")
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to generate RSA signing key", e);
        }
    }

    /**
     * Decoder pinned to RS256 — a token signed with any other algorithm (HS256, or the
     * classic {@code alg=none}) is rejected, not silently accepted.
     */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey) {
        try {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to build JWT decoder", e);
        }
    }
}
