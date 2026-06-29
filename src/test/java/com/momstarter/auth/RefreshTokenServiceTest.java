package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class RefreshTokenServiceTest {

    @Autowired
    private RefreshTokenRepository tokens;
    @Autowired
    private UserRepository users;

    private RefreshTokenService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(tokens, Clock.systemUTC());
        User u = new User();
        u.setEmail("mom+" + UUID.randomUUID() + "@example.com");
        users.save(u);
        userId = u.getId();
    }

    @Test
    void mintThenRotate_issuesNewTokenAndConsumesOld() {
        RefreshTokenService.Issued minted = service.mintFamily(userId, "device-1", "Pixel");
        RefreshTokenService.Issued rotated = service.rotate(minted.rawToken(), "device-1");

        assertThat(rotated.rawToken()).isNotEqualTo(minted.rawToken());
        // the consumed token can never rotate again
        assertThatThrownBy(() -> service.rotate(minted.rawToken(), "device-1"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("token_reuse_detected");
    }

    @Test
    void reuseOfRotatedToken_revokesWholeFamily() {
        RefreshTokenService.Issued minted = service.mintFamily(userId, "device-1", null);
        RefreshTokenService.Issued rotated = service.rotate(minted.rawToken(), "device-1");

        // replay of the already-rotated token = theft signal
        assertThatThrownBy(() -> service.rotate(minted.rawToken(), "device-1"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("token_reuse_detected");

        // the legit latest token is now dead too — the whole family was revoked
        assertThatThrownBy(() -> service.rotate(rotated.rawToken(), "device-1"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("invalid_token");
    }

    @Test
    void rotateAfterRevokeFamily_isInvalid() {
        RefreshTokenService.Issued minted = service.mintFamily(userId, "device-1", null);
        RefreshToken row = tokens.findByTokenHash(RefreshTokenService.sha256Hex(minted.rawToken())).orElseThrow();
        service.revokeFamily(row.getFamilyId());

        assertThatThrownBy(() -> service.rotate(minted.rawToken(), "device-1"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("invalid_token");
    }

    @Test
    void rawTokenIsNeverStored_onlyItsSha256() {
        RefreshTokenService.Issued minted = service.mintFamily(userId, "device-1", null);
        assertThat(tokens.findByTokenHash(minted.rawToken())).isEmpty();
        assertThat(tokens.findByTokenHash(RefreshTokenService.sha256Hex(minted.rawToken()))).isPresent();
    }
}
