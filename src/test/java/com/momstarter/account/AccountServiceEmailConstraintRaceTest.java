package com.momstarter.account;

import com.momstarter.account.dto.AccountInput;
import com.momstarter.auth.RefreshTokenService;
import com.momstarter.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the TOCTOU email-uniqueness race in AccountService.patchAccount.
 *
 * <p>Scenario: two concurrent PATCHes to the same new email both pass the
 * {@code existsByEmail} check (the "first" wins the DB write); the second
 * {@code saveAndFlush} then hits the {@code users.email} unique constraint.
 * Without a catch, Hibernate propagates {@link DataIntegrityViolationException}
 * → Spring MVC returns 500, leaking that a constraint was violated and
 * implicitly revealing that the email address is in use (violates C7/§H).
 *
 * <p>The fix must remap this to {@code 422 validation_error} — the same
 * non-enumerating body as the pre-check path.
 */
class AccountServiceEmailConstraintRaceTest {

    private UserRepository users;
    private RefreshTokenService refreshTokens;
    private AccountService service;

    @BeforeEach
    void setup() {
        users = mock(UserRepository.class);
        refreshTokens = mock(RefreshTokenService.class);
        AccountDekRepository accountDekRepository = mock(AccountDekRepository.class);
        service = new AccountService(users, refreshTokens, accountDekRepository);
    }

    @Test
    void patchAccount_whenSaveFlushHitsUniqueConstraint_returns422NotEnumerating() {
        // Arrange — active user with email "original@example.com", version 0
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setEmail("original@example.com");
        user.setLocale("th");

        when(users.findById(userId)).thenReturn(Optional.of(user));
        // Simulate the race: existsByEmail() passed (returned false), but the DB
        // constraint fires on saveAndFlush because another request committed first.
        when(users.existsByEmail("raced@example.com")).thenReturn(false);
        when(users.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint on users.email"));

        AccountInput input = new AccountInput("raced@example.com", null);

        // Act + Assert — must be ApiException(422, "validation_error"), NOT a raw
        // DataIntegrityViolationException (which Spring MVC would map to 500).
        assertThatThrownBy(() -> service.patchAccount(userId, input, "\"0\""))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException ae = (ApiException) ex;
                    assertThat(ae.getStatus()).isEqualTo(422);
                    assertThat(ae.getCode()).isEqualTo("validation_error");
                });
    }
}
