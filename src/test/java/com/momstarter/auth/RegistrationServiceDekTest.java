package com.momstarter.auth;

import com.momstarter.account.DekService;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.dto.AuthTokens;
import com.momstarter.auth.dto.VerifyEmailRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the DEK provisioning wiring in {@link RegistrationService}.
 *
 * <p>Verifies (ADR Decision 2 / IMPORTANT-4):
 * <ul>
 *   <li>{@link RegistrationService#verifyEmail} calls {@code DekService.provisionDek}
 *       with the verified user's id after saving the user.</li>
 *   <li>If {@code DekService.provisionDek} throws (KMS unavailable), the email
 *       verification still succeeds and valid {@link AuthTokens} are returned —
 *       i.e. KMS is NOT a hard dependency of the registration transaction.</li>
 * </ul>
 *
 * <p>Note on unit-test user-id handling: in unit tests, {@code User.getId()} returns null
 * because {@code @PrePersist} does not run. The service's DEK provisioning call uses
 * {@code userId} (from {@code emailVerification.consume()}) rather than {@code user.getId()},
 * so the DEK assertion uses the exact UUID. Other service calls (refreshTokens, jwt) use
 * {@code user.getId()} (null here) and are verified with {@code any()} matchers.
 *
 * <p>TDD: tests were written before the DEK provisioning code was added to RegistrationService.
 */
class RegistrationServiceDekTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final PasswordPolicy passwordPolicy = new PasswordPolicy(raw -> false);
    private final EmailVerificationService emailVerification = mock(EmailVerificationService.class);
    private final VerificationEmailSender sender = mock(VerificationEmailSender.class);
    private final JwtService jwt = mock(JwtService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final DekService dekService = mock(DekService.class);

    private final RegistrationService service = new RegistrationService(
            users, encoder, passwordPolicy, emailVerification, sender, jwt, refreshTokens,
            rateLimiter, dekService, 1_000_000, 1_000_000, 1_000_000, false);

    private final UUID userId = UUID.randomUUID();

    // -------------------------------------------------------------------------
    // verifyEmail — DEK provisioning wired
    // -------------------------------------------------------------------------

    @Test
    void verifyEmail_provisionsDekForVerifiedUser() {
        setupVerifyEmailMocks();

        service.verifyEmail(new VerifyEmailRequest("valid-token", "device-1"), "1.2.3.4");

        // DEK provisioning was called with the exact userId from emailVerification.consume()
        verify(dekService).provisionDek(userId);
    }

    // -------------------------------------------------------------------------
    // KMS-outside-txn: KMS failure does not prevent successful verifyEmail
    // -------------------------------------------------------------------------

    @Test
    void verifyEmail_kmsDown_stillReturnsAuthTokens() {
        setupVerifyEmailMocks();
        doThrow(new RuntimeException("KMS unavailable"))
                .when(dekService).provisionDek(any(UUID.class));

        // Even with KMS down, email verification must succeed and return tokens
        AuthTokens tokens = service.verifyEmail(new VerifyEmailRequest("valid-token", "device-1"), "1.2.3.4");

        assertThat(tokens).isNotNull();
        assertThat(tokens.accessToken()).isNotNull();
        assertThat(tokens.refreshToken()).isNotNull();
    }

    @Test
    void verifyEmail_kmsDown_stillMintsRefreshTokenFamily() {
        setupVerifyEmailMocks();
        doThrow(new RuntimeException("simulated KMS timeout"))
                .when(dekService).provisionDek(any(UUID.class));

        service.verifyEmail(new VerifyEmailRequest("valid-token", "device-1"), "1.2.3.4");

        // Refresh-token family minted even though DEK provisioning failed.
        // any() used for userId because user.getId() returns null in unit-test context
        // (User @PrePersist does not run here). The service correctly calls user.getId()
        // for mintFamily; the DEK call correctly uses `userId` from emailVerification.consume().
        verify(refreshTokens).mintFamily(any(), eq("device-1"), eq(null));
    }

    // -------------------------------------------------------------------------
    // verifyEmail — DEK provisioning NOT called if user not found
    // -------------------------------------------------------------------------

    @Test
    void verifyEmail_userNotFound_doesNotCallProvisionDek() {
        doNothing().when(rateLimiter).check(anyString(), anyInt(), any());
        when(emailVerification.consume("bad-token")).thenReturn(userId);
        when(users.findById(userId)).thenReturn(Optional.empty());

        try {
            service.verifyEmail(new VerifyEmailRequest("bad-token", "device-1"), "1.2.3.4");
        } catch (Exception ignored) {
            // ApiException 410 expected — user not found
        }

        verify(dekService, never()).provisionDek(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Sets up mocks for a successful verifyEmail flow.
     * Uses {@code any()} for matchers on paths that go through {@code user.getId()}
     * (which returns null in unit tests because @PrePersist does not run).
     */
    private void setupVerifyEmailMocks() {
        User user = new User();
        // user.getId() returns null — @PrePersist does not run in unit tests.
        // The service's DEK call uses `userId` (from emailVerification.consume) — non-null.

        doNothing().when(rateLimiter).check(anyString(), anyInt(), any());
        when(emailVerification.consume("valid-token")).thenReturn(userId);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(users.save(any())).thenReturn(user);

        RefreshTokenService.Issued issued = mock(RefreshTokenService.Issued.class);
        when(issued.rawToken()).thenReturn("refresh-token-xyz");
        // any() for userId because user.getId() is null in unit test
        when(refreshTokens.mintFamily(any(), eq("device-1"), eq(null))).thenReturn(issued);
        when(jwt.issueAccessToken(any(), eq(true))).thenReturn("access-token-xyz");
        when(jwt.accessTtlSeconds()).thenReturn(900L);
    }
}
