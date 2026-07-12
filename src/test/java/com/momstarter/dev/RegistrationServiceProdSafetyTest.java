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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POISON TEST (fail-on-revert) for the Pass-1 regression: {@code RegistrationService} used to
 * bind {@code momstarter.dev.auto-verify-email} directly via {@code @Value}, completely bypassing
 * the {@code @Profile("!prod")} firewall that gates {@link DevModeGuard} / {@link DevModeSeeder}.
 * A plain {@code @Service} is constructed in EVERY profile including {@code prod}, so a poisoned
 * property (operator mistake, leaked local config, etc.) would silently set
 * {@code emailVerified=true} and skip the verification email in production — the worst of the
 * three possible outcomes (crash / inert / silently-effective).
 *
 * <p>This test proves the fix holds: {@link RegistrationService} now receives the flag's
 * EFFECTIVE value through {@code Optional<DevFlags>} (see {@link DevFlags}, itself
 * {@code @Profile("!prod")}-gated). Under prod, no {@link DevFlags} bean exists, so the
 * {@code Optional} is empty and the effective value is forced {@code false} — independent of
 * whatever the raw {@code momstarter.dev.auto-verify-email} property says.
 *
 * <p>THE GAP THIS CLOSES: {@code ProdProfileRejectsDevBeansTest} only asserted that the four
 * dev-only beans are absent under a poisoned prod context. It never asserted anything about
 * {@link RegistrationService}'s own *behaviour* — which is exactly how the regression escaped
 * detection. This test asserts the behavioural outcome directly: simulate the poisoned-prod
 * wiring (no {@code DevFlags} bean, i.e. {@code Optional.empty()}) and confirm a brand-new
 * registration is NOT auto-verified and DOES go through the normal two-phase email-verification
 * flow.
 */
class RegistrationServiceProdSafetyTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final PasswordPolicy passwordPolicy = new PasswordPolicy(raw -> false);
    private final EmailVerificationService emailVerification = mock(EmailVerificationService.class);
    private final VerificationEmailSender sender = mock(VerificationEmailSender.class);
    private final JwtService jwt = mock(JwtService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final DekService dekService = mock(DekService.class);

    /**
     * Simulates the exact prod poison scenario: {@code momstarter.dev.auto-verify-email=true} is
     * set (operator mistake / leaked config) but the app is running under the {@code prod}
     * profile, so Spring never constructs a {@link DevFlags} bean ({@code @Profile("!prod")}
     * excludes it at bean-creation time). The only way for that raw property value to reach
     * {@link RegistrationService} would be if it still bound {@code @Value} directly — which is
     * exactly the regression. With the fix, {@code RegistrationService} is constructed with
     * {@code Optional.empty()} here, mirroring what a real prod context wires.
     */
    @Test
    void prodPoison_devFlagsBeanAbsent_registrationDoesNotAutoVerify_stillSendsVerificationEmail() {
        RegistrationService svc = new RegistrationService(
                users, encoder, passwordPolicy, emailVerification, sender, jwt, refreshTokens,
                rateLimiter, dekService, 1_000_000, 1_000_000, 1_000_000,
                Optional.empty() /* DevFlags bean absent under prod, exactly as a real prod
                                    context wires it, regardless of the raw property value */);

        when(encoder.encode(anyString())).thenReturn("{argon2}fakehash");
        when(users.findByEmail("victim@prod.example")).thenReturn(Optional.empty());
        when(emailVerification.issue(any())).thenReturn("rawtoken");

        svc.register(new RegisterRequest("victim@prod.example", "correcthorsebattery", "th", null),
                "203.0.113.7");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().isEmailVerified())
                .as("PROD POISON GATE: with the DevFlags bean absent (prod profile), a new "
                        + "registration must NEVER be auto-verified, no matter what the raw "
                        + "momstarter.dev.auto-verify-email property says")
                .isFalse();
        verify(sender).sendVerification(anyString(), anyString());
    }

    /** Sanity counterpart: an explicitly-false DevFlags (normal prod-shaped config) behaves the
     *  same as the bean being absent — belt-and-suspenders, not the primary assertion above. */
    @Test
    void devFlagsPresentButFalse_registrationDoesNotAutoVerify() {
        RegistrationService svc = new RegistrationService(
                users, encoder, passwordPolicy, emailVerification, sender, jwt, refreshTokens,
                rateLimiter, dekService, 1_000_000, 1_000_000, 1_000_000,
                Optional.of(new DevFlags(false)));

        when(encoder.encode(anyString())).thenReturn("{argon2}fakehash");
        when(users.findByEmail("new@test.local")).thenReturn(Optional.empty());
        when(emailVerification.issue(any())).thenReturn("rawtoken");

        svc.register(new RegisterRequest("new@test.local", "correcthorsebattery", "th", null),
                "127.0.0.1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().isEmailVerified()).isFalse();
        verify(sender).sendVerification(anyString(), anyString());
    }

    /** Positive control: DevFlags present and true (genuine local dev profile) still enables the
     *  shortcut — proves the fix did not simply neuter the feature everywhere. */
    @Test
    void devFlagsPresentAndTrue_localDevProfile_stillAutoVerifies() {
        RegistrationService svc = new RegistrationService(
                users, encoder, passwordPolicy, emailVerification, sender, jwt, refreshTokens,
                rateLimiter, dekService, 1_000_000, 1_000_000, 1_000_000,
                Optional.of(new DevFlags(true)));

        when(encoder.encode(anyString())).thenReturn("{argon2}fakehash");
        when(users.findByEmail("dev@test.local")).thenReturn(Optional.empty());

        svc.register(new RegisterRequest("dev@test.local", "correcthorsebattery", "th", null),
                "127.0.0.1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().isEmailVerified()).isTrue();
        verify(sender, never()).sendVerification(anyString(), anyString());
    }
}
