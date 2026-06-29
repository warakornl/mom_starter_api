package com.momstarter.auth;

import com.momstarter.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private PasswordPolicy policyWithBreach(boolean breached) {
        // inject a fake breached-password checker
        return new PasswordPolicy(raw -> breached);
    }

    @Test
    void rejectsTooShortPassword() {
        assertThatThrownBy(() -> policyWithBreach(false).validate("abc"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("password_too_short");
    }

    @Test
    void rejectsBreachedPassword() {
        assertThatThrownBy(() -> policyWithBreach(true).validate("longenoughpassword"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("password_breached");
    }

    @Test
    void acceptsStrongFreshPassword() {
        assertThatCode(() -> policyWithBreach(false).validate("longenoughpassword"))
                .doesNotThrowAnyException();
    }
}
