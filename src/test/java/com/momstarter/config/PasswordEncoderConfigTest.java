package com.momstarter.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderConfigTest {

    @Test
    void encodesWithBcryptAndMatches() {
        PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

        String hash = encoder.encode("longenoughpassword");

        assertThat(hash).startsWith("{bcrypt}$2");
        assertThat(encoder.matches("longenoughpassword", hash)).isTrue();
        assertThat(encoder.matches("wrongpassword", hash)).isFalse();
    }
}
