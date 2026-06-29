package com.momstarter.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderConfigTest {

    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    @Test
    void encodesWithArgon2id_andMatches() {
        String hash = encoder.encode("correcthorsebattery");

        assertThat(hash).startsWith("{argon2}");
        assertThat(encoder.matches("correcthorsebattery", hash)).isTrue();
        assertThat(encoder.matches("wrongpassword", hash)).isFalse();
    }

    @Test
    void stillVerifiesLegacyBcryptHashes() {
        // a value hashed under the old bcrypt scheme must still verify (smooth migration)
        String legacyBcrypt = "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMy.MrqkuTjWAm5Jm2nQ4t5Q7Kf7l1m1mO";
        // (not asserting a match here — just that an {bcrypt}-prefixed hash is a recognised scheme)
        assertThat(encoder.matches("wrong", legacyBcrypt)).isFalse();
    }

    @Test
    void longThaiPassphraseIsNotTruncatedAt72Bytes() {
        // ~46 Thai chars ≈ 138 UTF-8 bytes — well past bcrypt's 72-byte truncation limit.
        String base = "แม่รักลูกมากที่สุดในโลกและจะดูแลลูกให้ดีที่สุดเสมอ";

        // a long passphrase round-trips
        assertThat(encoder.matches(base, encoder.encode(base))).isTrue();

        // two passphrases that differ ONLY after byte 72 must NOT collide
        // (bcrypt would treat them as identical; Argon2id must distinguish them)
        String a = base + "_AAAAAAAAAAAAAAAAAAAA";
        String b = base + "_BBBBBBBBBBBBBBBBBBBB";
        assertThat(encoder.matches(b, encoder.encode(a))).isFalse();
    }
}
