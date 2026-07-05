package com.momstarter.dev;

import com.momstarter.account.DekService;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.EmailVerificationService;
import com.momstarter.auth.JwtService;
import com.momstarter.auth.PasswordPolicy;
import com.momstarter.auth.RateLimiter;
import com.momstarter.auth.RefreshTokenService;
import com.momstarter.auth.RegistrationService;
import com.momstarter.auth.VerificationEmailSender;
import com.momstarter.auth.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Part A — tests that momstarter.dev.auto-verify-email controls whether a newly registered
 * user gets emailVerified=true immediately (dev shortcut) or goes through the normal two-phase
 * verification flow. The flag must default to false — turning it on must be an explicit
 * local-only action.
 */
class DevModeAutoVerifyTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final PasswordPolicy passwordPolicy = new PasswordPolicy(raw -> false);
    private final EmailVerificationService emailVerification = mock(EmailVerificationService.class);
    private final VerificationEmailSender sender = mock(VerificationEmailSender.class);
    private final JwtService jwt = mock(JwtService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final DekService dekService = mock(DekService.class);

    // ── Part A-1: flag ON ──────────────────────────────────────────────────────────

    @Test
    void flagOn_newEmail_setsEmailVerifiedTrue_skipsEmailAndToken() {
        RegistrationService svc = serviceWith(/*autoVerifyEmail=*/ true);
        when(encoder.encode(anyString())).thenReturn("{argon2}fakehash");
        when(users.findByEmail("dev@test.local")).thenReturn(Optional.empty());

        svc.register(new RegisterRequest("dev@test.local", "correcthorsebattery", "th", null), "127.0.0.1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().isEmailVerified())
                .as("auto-verify ON: emailVerified must be true immediately after registration")
                .isTrue();
        // No verification token is created and no email is sent
        verify(emailVerification, never()).issue(any());
        verify(sender, never()).sendVerification(anyString(), anyString());
    }

    @Test
    void flagOn_collision_behavesUnchanged_sendsAlreadyRegisteredNotice() {
        // Existing-email path must be untouched by the flag (constant-time, non-enumerating)
        RegistrationService svc = serviceWith(/*autoVerifyEmail=*/ true);
        when(encoder.encode(anyString())).thenReturn("{argon2}fakehash");
        when(users.findByEmail("taken@test.local")).thenReturn(Optional.of(new User()));

        svc.register(new RegisterRequest("taken@test.local", "correcthorsebattery", "th", null), "127.0.0.1");

        verify(users, never()).save(any());
        verify(sender).sendAlreadyRegisteredNotice("taken@test.local");
        verify(sender, never()).sendVerification(anyString(), anyString());
    }

    // ── Part A-2: flag OFF (default — existing behaviour must not change) ─────────

    @Test
    void flagOff_newEmail_setsEmailVerifiedFalse_sendsVerificationEmail() {
        RegistrationService svc = serviceWith(/*autoVerifyEmail=*/ false);
        when(encoder.encode(anyString())).thenReturn("{argon2}fakehash");
        when(users.findByEmail("new@test.local")).thenReturn(Optional.empty());
        when(emailVerification.issue(any())).thenReturn("rawtoken");

        svc.register(new RegisterRequest("new@test.local", "correcthorsebattery", "th", null), "127.0.0.1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().isEmailVerified())
                .as("flag OFF: emailVerified must still be false (two-phase flow)")
                .isFalse();
        verify(sender).sendVerification(eq("new@test.local"), eq("rawtoken"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private RegistrationService serviceWith(boolean autoVerifyEmail) {
        return new RegistrationService(
                users, encoder, passwordPolicy, emailVerification, sender, jwt, refreshTokens,
                rateLimiter, dekService, 1_000_000, 1_000_000, 1_000_000, autoVerifyEmail);
    }
}
