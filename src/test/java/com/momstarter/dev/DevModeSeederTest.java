package com.momstarter.dev;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Part B — tests for the dev-mode seed account runner. Verifies:
 *  1. A missing account is created with emailVerified=true and correct credentials.
 *  2. Running twice (account already present) creates no duplicate — idempotent.
 */
class DevModeSeederTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final DevModeSeeder seeder = new DevModeSeeder(users, encoder);

    @Test
    void whenAccountAbsent_createsDevAccountWithEmailVerifiedTrue() throws Exception {
        when(users.existsByEmail(DevModeSeeder.DEV_EMAIL)).thenReturn(false);
        when(encoder.encode(DevModeSeeder.DEV_PASSWORD_RAW)).thenReturn("{argon2}$hashedSecret$");

        seeder.run(new DefaultApplicationArguments(new String[0]));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo(DevModeSeeder.DEV_EMAIL);
        assertThat(saved.isEmailVerified())
                .as("seed account must have emailVerified=true so tester can log in immediately")
                .isTrue();
        assertThat(saved.getPasswordHash()).isEqualTo("{argon2}$hashedSecret$");
    }

    @Test
    void whenAccountAlreadyExists_doesNotCreateDuplicate_isIdempotent() throws Exception {
        when(users.existsByEmail(DevModeSeeder.DEV_EMAIL)).thenReturn(true);

        seeder.run(new DefaultApplicationArguments(new String[0]));

        verify(users, never()).save(any());
        // No encoding happens either (no-op)
        verify(encoder, never()).encode(anyString());
    }

    @Test
    void passwordRawConstant_satisfiesPasswordPolicy() {
        // The password must be long enough (>=8 chars) for the PasswordPolicy and
        // easy enough to remember for testers. The format is mixed-case + digit + hyphen.
        assertThat(DevModeSeeder.DEV_PASSWORD_RAW.length())
                .as("dev password must be at least 8 characters")
                .isGreaterThanOrEqualTo(8);
        // Must not be blank
        assertThat(DevModeSeeder.DEV_PASSWORD_RAW).isNotBlank();
    }
}
