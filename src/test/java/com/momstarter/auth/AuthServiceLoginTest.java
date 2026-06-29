package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.dto.AuthTokens;
import com.momstarter.auth.dto.LoginRequest;
import com.momstarter.config.JwtKeyConfig;
import com.momstarter.config.SecurityConfig;
import com.momstarter.error.ApiException;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class AuthServiceLoginTest {

    @Autowired
    private UserRepository users;
    @Autowired
    private RefreshTokenRepository tokens;

    private AuthService auth;
    private PasswordEncoder encoder;
    private LoginAttemptService loginAttempts;
    private JwtDecoder decoder;
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() throws Exception {
        encoder = new SecurityConfig().passwordEncoder();
        JwtKeyConfig keyConfig = new JwtKeyConfig();
        RSAKey key = keyConfig.rsaKey();
        JwtService jwt = new JwtService(key);
        decoder = keyConfig.jwtDecoder(key);
        Clock clock = Clock.systemUTC();
        RefreshTokenService refreshTokens = new RefreshTokenService(tokens, clock);
        loginAttempts = new LoginAttemptService(clock);
        rateLimiter = mock(RateLimiter.class); // no-op by default; one test stubs it to throw
        auth = new AuthService(users, encoder, jwt, refreshTokens, loginAttempts, rateLimiter, 1_000_000);
    }

    private User seedUser(String email, String rawPassword, boolean verified) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setEmailVerified(verified);
        users.save(u);
        return u;
    }

    @Test
    void validCredentials_returnTokensAndMintRefreshFamily() {
        seedUser("mom@example.com", "correcthorsebattery", true);

        AuthTokens t = auth.login(new LoginRequest("mom@example.com", "correcthorsebattery", "device-1"), "1.2.3.4");

        assertThat(t.accessToken()).isNotBlank();
        assertThat(t.refreshToken()).isNotBlank();
        assertThat(t.accessTokenExpiresIn()).isPositive();
        assertThat(t.refreshTokenExpiresIn()).isPositive();
        assertThat(tokens.findByTokenHash(RefreshTokenService.sha256Hex(t.refreshToken()))).isPresent();
        assertThat(decoder.decode(t.accessToken()).getClaimAsBoolean("email_verified")).isTrue();
    }

    @Test
    void unknownEmail_yieldsGenericInvalidCredentials() {
        assertThatThrownBy(() -> auth.login(new LoginRequest("ghost@example.com", "whateverpass", "d"), "ip"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("invalid_credentials");
    }

    @Test
    void wrongPassword_yieldsTheSameGenericInvalidCredentials() {
        seedUser("mom@example.com", "correcthorsebattery", true);

        assertThatThrownBy(() -> auth.login(new LoginRequest("mom@example.com", "wrongpassword", "d"), "ip"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("invalid_credentials");
    }

    @Test
    void registeredButUnverified_loginsToUnverifiedSession() {
        seedUser("mom@example.com", "correcthorsebattery", false);

        AuthTokens t = auth.login(new LoginRequest("mom@example.com", "correcthorsebattery", "d"), "ip");

        assertThat(decoder.decode(t.accessToken()).getClaimAsBoolean("email_verified")).isFalse();
    }

    @Test
    void lockedAccount_yields429RateLimited() {
        // Contract §H: soft-lock MUST return 429 rate_limited (not account_locked — mobile diffs on rate_limited)
        seedUser("mom@example.com", "correcthorsebattery", true);
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            loginAttempts.recordFailure("mom@example.com");
        }

        assertThatThrownBy(() -> auth.login(new LoginRequest("mom@example.com", "correcthorsebattery", "d"), "ip"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException a = (ApiException) ex;
                    assertThat(a.getStatus()).isEqualTo(429);
                    assertThat(a.getCode()).isEqualTo("rate_limited");
                });
    }

    @Test
    void login_isThrottledPerIp() {
        seedUser("mom@example.com", "correcthorsebattery", true);
        doThrow(new ApiException(429, "rate_limited"))
                .when(rateLimiter).check(startsWith("login-ip:"), anyInt(), any());

        assertThatThrownBy(() -> auth.login(new LoginRequest("mom@example.com", "correcthorsebattery", "d"), "9.9.9.9"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("rate_limited");
    }

    @Test
    void successResetsTheFailureCounter() {
        seedUser("mom@example.com", "correcthorsebattery", true);
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            try {
                auth.login(new LoginRequest("mom@example.com", "bad" + i, "d"), "ip");
            } catch (ApiException ignored) {
                // expected invalid_credentials
            }
        }
        // a success here must reset the counter...
        auth.login(new LoginRequest("mom@example.com", "correcthorsebattery", "d"), "ip");
        // ...so another near-threshold run still does not lock
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            try {
                auth.login(new LoginRequest("mom@example.com", "bad" + i, "d"), "ip");
            } catch (ApiException ignored) {
            }
        }
        assertThatCode(() -> auth.login(new LoginRequest("mom@example.com", "correcthorsebattery", "d"), "ip"))
                .doesNotThrowAnyException();
    }
}
