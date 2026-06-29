package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.dto.AuthTokens;
import com.momstarter.config.JwtKeyConfig;
import com.momstarter.error.ApiException;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class GoogleSignInServiceTest {

    @Autowired
    private AuthIdentityRepository identities;
    @Autowired
    private UserRepository users;
    @Autowired
    private RefreshTokenRepository tokens;

    private GoogleIdTokenVerifier verifier;
    private GoogleSignInService service;

    @BeforeEach
    void setUp() throws Exception {
        verifier = mock(GoogleIdTokenVerifier.class);
        RSAKey key = new JwtKeyConfig().rsaKey();
        JwtService jwt = new JwtService(key);
        RefreshTokenService refreshTokens = new RefreshTokenService(tokens, Clock.systemUTC());
        service = new GoogleSignInService(verifier, identities, users, refreshTokens, jwt);
    }

    @Test
    void brandNewUser_createsUserAndLink_mintsSession() {
        when(verifier.verify("tok", "nonce")).thenReturn(new GoogleIdentity("sub-1", "new@example.com", true));

        AuthTokens t = service.signIn("tok", "nonce", "dev-1");

        assertThat(t.accessToken()).isNotBlank();
        assertThat(t.refreshToken()).isNotBlank();
        assertThat(users.findByEmail("new@example.com")).isPresent();
        assertThat(identities.findByProviderAndProviderSub("google", "sub-1")).isPresent();
    }

    @Test
    void returningFederatedUser_signsInWithoutCreatingAnother() {
        User u = new User();
        u.setEmail("mom@example.com");
        u.setEmailVerified(true);
        users.save(u);
        AuthIdentity link = new AuthIdentity();
        link.setUserId(u.getId());
        link.setProvider("google");
        link.setProviderSub("sub-2");
        link.setEmail("mom@example.com");
        identities.save(link);
        long before = users.count();

        when(verifier.verify("tok", "nonce")).thenReturn(new GoogleIdentity("sub-2", "mom@example.com", true));
        AuthTokens t = service.signIn("tok", "nonce", "dev-1");

        assertThat(t.accessToken()).isNotBlank();
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void emailCollisionWithoutLink_returns409LinkRequired() {
        User existing = new User();
        existing.setEmail("taken@example.com");
        existing.setPasswordHash("{argon2}whatever");
        users.save(existing);

        when(verifier.verify("tok", "nonce")).thenReturn(new GoogleIdentity("sub-3", "taken@example.com", true));

        assertThatThrownBy(() -> service.signIn("tok", "nonce", "dev-1"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("link_required");
    }

    @Test
    void unverifiedGoogleEmail_returns401_andCreatesNothing() {
        when(verifier.verify("tok", "nonce")).thenReturn(new GoogleIdentity("sub-x", "x@example.com", false));

        assertThatThrownBy(() -> service.signIn("tok", "nonce", "dev-1"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("google_token_invalid");
        assertThat(users.findByEmail("x@example.com")).isEmpty();
    }

    @Test
    void invalidGoogleToken_propagates401() {
        when(verifier.verify("bad", "nonce")).thenThrow(new ApiException(401, "google_token_invalid"));

        assertThatThrownBy(() -> service.signIn("bad", "nonce", "dev-1"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("google_token_invalid");
    }
}
