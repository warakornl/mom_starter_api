package com.momstarter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.HashMap;
import java.util.Map;

/**
 * Stateless security: no sessions, no CSRF (token API), Bearer-JWT resource server with
 * the alg-pinned decoder (JwtKeyConfig). The unauthenticated /auth surface is public;
 * everything else requires a valid access token.
 */
@Configuration
public class SecurityConfig {

    /** The unauthenticated auth surface (matches the contract's public-endpoint list). */
    static final String[] PUBLIC_PATHS = {
            "/auth/login",
            "/auth/refresh",
            "/auth/register",
            "/auth/verify-email",
            "/auth/resend-verification",
            "/auth/google",
            "/health"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {
                }));
        return http.build();
    }

    /**
     * Delegating encoder that ENCODES with Argon2id (no 72-byte truncation — supports long
     * Thai/Unicode passphrases, §2/§F) while still VERIFYING legacy {@code {bcrypt}} hashes for
     * a smooth migration.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        encoders.put("bcrypt", new BCryptPasswordEncoder());
        return new DelegatingPasswordEncoder("argon2", encoders);
    }
}
