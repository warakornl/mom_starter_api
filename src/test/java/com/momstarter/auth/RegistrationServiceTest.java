package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.dto.RegisterRequest;
import com.momstarter.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Constant-time defence for register (§E): the bcrypt cost must be paid on BOTH the new-email
 * and the colliding-email branch, so response timing cannot be used to enumerate accounts.
 */
class RegistrationServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final PasswordPolicy passwordPolicy = new PasswordPolicy(raw -> false);
    private final EmailVerificationService emailVerification = mock(EmailVerificationService.class);
    private final VerificationEmailSender sender = mock(VerificationEmailSender.class);
    private final JwtService jwt = mock(JwtService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);

    private final RegistrationService service = new RegistrationService(
            users, encoder, passwordPolicy, emailVerification, sender, jwt, refreshTokens,
            rateLimiter, 1_000_000, 1_000_000, 1_000_000, false);

    @Test
    void collidingEmail_stillRunsBcrypt_andCreatesNoUser() {
        when(encoder.encode(anyString())).thenReturn("{bcrypt}$2a$10$dummy");
        when(users.findByEmail("mom@example.com")).thenReturn(Optional.of(new User()));

        service.register(new RegisterRequest("mom@example.com", "correcthorsebattery", null, null), "1.2.3.4");

        // bcrypt cost is paid even though the account exists (constant-time vs the new-email path)
        verify(encoder).encode("correcthorsebattery");
        // ...but nothing is created and the collision notice is what's sent
        verify(users, never()).save(any());
        verify(sender).sendAlreadyRegisteredNotice("mom@example.com");
        verify(sender, never()).sendVerification(anyString(), anyString());
    }

    @Test
    void newEmail_runsBcrypt_createsUser_sendsVerification() {
        when(encoder.encode(anyString())).thenReturn("{bcrypt}$2a$10$dummy");
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(emailVerification.issue(any())).thenReturn("rawtoken");

        service.register(new RegisterRequest("new@example.com", "correcthorsebattery", "th", null), "1.2.3.4");

        verify(encoder).encode("correcthorsebattery");
        verify(users).save(any(User.class));
        verify(sender).sendVerification(eq("new@example.com"), eq("rawtoken"));
    }

    @Test
    void register_isThrottledPerIp() {
        doThrow(new ApiException(429, "rate_limited"))
                .when(rateLimiter).check(startsWith("register-ip:"), anyInt(), any());

        assertThatThrownBy(() -> service.register(
                new RegisterRequest("x@example.com", "correcthorsebattery", null, null), "9.9.9.9"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("rate_limited");
        verify(users, never()).save(any());
    }

    @Test
    void verifyEmail_isThrottledPerIp() {
        // Contract §H: POST /auth/verify-email must be rate-limited per IP to prevent token guessing
        doThrow(new ApiException(429, "rate_limited"))
                .when(rateLimiter).check(startsWith("verify-email-ip:"), anyInt(), any());

        assertThatThrownBy(() -> service.verifyEmail(
                new com.momstarter.auth.dto.VerifyEmailRequest("sometoken", "d1"), "9.9.9.9"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("rate_limited");
    }
}
