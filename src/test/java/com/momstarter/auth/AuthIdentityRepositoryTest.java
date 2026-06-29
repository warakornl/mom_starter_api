package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class AuthIdentityRepositoryTest {

    @Autowired
    private AuthIdentityRepository identities;
    @Autowired
    private UserRepository users;

    @Test
    void savesAndFindsByProviderAndSub() {
        User u = new User();
        u.setEmail("mom@example.com");
        users.save(u);

        AuthIdentity identity = new AuthIdentity();
        identity.setUserId(u.getId());
        identity.setProvider("google");
        identity.setProviderSub("google-sub-123");
        identity.setEmail("mom@example.com");
        identities.save(identity);

        assertThat(identities.findByProviderAndProviderSub("google", "google-sub-123")).isPresent();
        assertThat(identities.findByProviderAndProviderSub("google", "nope")).isEmpty();
        assertThat(identities.findByProviderAndProviderSub("google", "google-sub-123").orElseThrow().getId())
                .isNotNull();
    }
}
