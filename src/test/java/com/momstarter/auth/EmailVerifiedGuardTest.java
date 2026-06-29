package com.momstarter.auth;

import com.momstarter.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerifiedGuardTest {

    private final EmailVerifiedGuard guard = new EmailVerifiedGuard();

    private Jwt jwtWithEmailVerified(boolean verified) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("00000000-0000-0000-0000-000000000001")
                .claim("email_verified", verified)
                .build();
    }

    @Test
    void verifiedSessionPasses() {
        assertThatCode(() -> guard.requireVerified(jwtWithEmailVerified(true)))
                .doesNotThrowAnyException();
    }

    @Test
    void unverifiedSessionIsRejectedWith403() {
        assertThatThrownBy(() -> guard.requireVerified(jwtWithEmailVerified(false)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("email_unverified");
    }
}
