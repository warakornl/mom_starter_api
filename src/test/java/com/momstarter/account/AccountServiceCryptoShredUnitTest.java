package com.momstarter.account;

import com.momstarter.auth.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AccountService#deleteAccount} — T0 crypto-shred wiring (sub-slice c).
 *
 * <p>Tests (c) and (d) from the crypto-shred spec:
 * <ul>
 *   <li><strong>(c) Ordering:</strong> The T0 crypto-shred ({@link AccountDekRepository#deleteByUserId})
 *       executes BEFORE {@code revokeAllForUser} (ADR CRITICAL-1 / migration-design T0 sequence).</li>
 *   <li><strong>(d) Fail-closed:</strong> If {@code deleteByUserId} throws a {@code RuntimeException},
 *       {@code deleteAccount} MUST propagate it. Spring's {@code @Transactional} AOP then rolls back
 *       the entire transaction — including the {@code setStatus("deleted")} write — so the user is NOT
 *       left in the {@code "deleted"} state after a failed shred attempt.</li>
 * </ul>
 *
 * <p>These tests run without a Spring context (plain Mockito). The actual DB rollback on exception
 * propagation is a Spring {@code @Transactional} guarantee; the tests verify only that the exception
 * is NOT swallowed (fail-closed contract) and that token revocation is NOT triggered when the shred fails.
 *
 * <p>TDD: tests written before {@link AccountService} was wired with {@link AccountDekRepository}.
 * They fail until the constructor injection and {@code deleteByUserId} call are added.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceCryptoShredUnitTest {

    @Mock private UserRepository users;
    @Mock private RefreshTokenService refreshTokens;
    @Mock private AccountDekRepository accountDekRepository;

    @InjectMocks private AccountService service;

    // -----------------------------------------------------------------------
    // (c) Shred must precede token revocation
    // -----------------------------------------------------------------------

    /**
     * ADR CRITICAL-1 / migration-design T0 sequence line order:
     * {@code users.saveAndFlush(user)} (persists setStatus("deleted"))
     * → {@code accountDekRepository.deleteByUserId(userId)} (T0 crypto-shred)
     * → {@code refreshTokens.revokeAllForUser(userId)} (session revocation)
     *
     * <p>The DEK must be destroyed before sessions are revoked. If the order were reversed,
     * a device with an in-flight token could technically re-fetch the DEK between revocation
     * and shred — a brief residual window. The correct order eliminates this window.
     */
    @Test
    void deleteAccount_cryptoShredPrecedesTokenRevoke_orderingGuaranteed() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        when(users.findById(userId)).thenReturn(Optional.of(user));

        service.deleteAccount(userId);

        InOrder inOrder = inOrder(users, accountDekRepository, refreshTokens);
        inOrder.verify(users).saveAndFlush(user);
        inOrder.verify(accountDekRepository).deleteByUserId(userId);
        inOrder.verify(refreshTokens).revokeAllForUser(userId);
    }

    // -----------------------------------------------------------------------
    // (d) Fail-closed: DEK delete throws → exception propagates, revoke not called
    // -----------------------------------------------------------------------

    /**
     * If {@code deleteByUserId} throws a {@code RuntimeException}:
     * <ol>
     *   <li>{@code deleteAccount} MUST propagate the exception (not swallow it).</li>
     *   <li>Spring's {@code @Transactional} AOP intercepts the propagating exception and
     *       rolls back the entire transaction (including the {@code setStatus("deleted")} flush).</li>
     *   <li>{@code revokeAllForUser} MUST NOT be called — the user is conceptually still active.</li>
     * </ol>
     *
     * <p>This test verifies points 1 and 3. Point 2 is a Spring framework guarantee
     * (not retested here).
     */
    @Test
    void deleteAccount_dekDeleteThrows_propagatesException_doesNotCallRevoke() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("simulated DEK shred failure"))
                .when(accountDekRepository).deleteByUserId(userId);

        assertThatThrownBy(() -> service.deleteAccount(userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated DEK shred failure");

        // Token revocation must NOT be reached if the shred threw
        verify(refreshTokens, never()).revokeAllForUser(any());
    }
}
