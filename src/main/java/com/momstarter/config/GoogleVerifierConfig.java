package com.momstarter.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Provides Google's JWKS as a (lazily-fetched, cached) key source for the ID-token verifier.
 * In tests the verifier is constructed directly with a local key source instead.
 */
@Configuration
public class GoogleVerifierConfig {

    @Bean
    public JWKSource<SecurityContext> googleJwkSource(
            @Value("${google.jwks-uri:https://www.googleapis.com/oauth2/v3/certs}") String jwksUri) throws Exception {
        return new RemoteJWKSet<>(URI.create(jwksUri).toURL());
    }
}
