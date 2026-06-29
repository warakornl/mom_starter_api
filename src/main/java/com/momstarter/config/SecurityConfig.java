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

    /**
     * The unauthenticated surface: auth endpoints + actuator health/prometheus.
     *
     * <p>Actuator paths are relative to the servlet context-path (/v1), so full URLs are
     * /v1/actuator/health and /v1/actuator/prometheus. Prometheus scrapes from within the
     * Docker internal network only — there is NO public internet access to these paths in prod.
     *
     * <p>PROD NOTE: the recommended hardening is to run actuator on a separate management
     * port (management.server.port=8081) with network-level restriction (security-group /
     * VPC-only), removing the need for Spring Security to guard them. See
     * docs/architecture/infrastructure-diagram.md for the target AWS network topology.
     */
    static final String[] PUBLIC_PATHS = {
            "/auth/login",
            "/auth/refresh",
            "/auth/register",
            "/auth/verify-email",
            "/auth/resend-verification",
            "/auth/forgot-password",
            "/auth/reset-password",
            "/auth/google",
            "/health",
            // Actuator: health check (load-balancer probe + Prometheus liveness) and metrics scrape.
            // Allowed from internal Docker network; must be blocked at network layer in prod.
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/prometheus"
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
