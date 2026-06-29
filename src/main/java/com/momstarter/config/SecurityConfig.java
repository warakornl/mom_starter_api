package com.momstarter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

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
     * Delegating encoder — encodes with bcrypt (prefix {@code {bcrypt}}) and can still
     * verify any other prefixed hash, so the algorithm can be upgraded later (§F).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
