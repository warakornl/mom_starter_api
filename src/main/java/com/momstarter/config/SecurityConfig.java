package com.momstarter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Security beans. For now this only exposes the password encoder; the stateless
 * filter chain + alg-pinned JWT decoder land in Task 1.6.
 */
@Configuration
public class SecurityConfig {

    /**
     * Delegating encoder — encodes with bcrypt (prefix {@code {bcrypt}}) and can still
     * verify any other prefixed hash, so the algorithm can be upgraded later (§F).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
