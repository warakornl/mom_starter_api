package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.LoginAttemptService;
import com.momstarter.auth.dto.LoginRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AuthChangePasswordMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private AuthService auth;
    @Autowired
    private JwtService jwt;

    private String bearer;
    private String currentDeviceRefresh;
    private String otherDeviceRefresh;

    @BeforeEach
    void seed() {
        User u = new User();
        u.setEmail("mom@example.com");
        u.setPasswordHash(encoder.encode("oldpassword123"));
        u.setEmailVerified(true);
        users.save(u);
        bearer = jwt.issueAccessToken(u.getId(), true);
        currentDeviceRefresh = auth.login(new LoginRequest("mom@example.com", "oldpassword123", "dev-current"), "ip").refreshToken();
        otherDeviceRefresh = auth.login(new LoginRequest("mom@example.com", "oldpassword123", "dev-other"), "ip").refreshToken();
    }

    private String body(String current, String next, String deviceId) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\",\"deviceId\":\"" + deviceId + "\"}";
    }

    @Test
    void changePassword_setsNew_keepsCurrentDevice_revokesOthers() throws Exception {
        mvc.perform(post("/auth/change-password").header("Authorization", "Bearer " + bearer)
                        .contentType(APPLICATION_JSON).content(body("oldpassword123", "newstrongpassword", "dev-current")))
                .andExpect(status().isNoContent());

        // new password works, old does not
        mvc.perform(post("/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"mom@example.com\",\"password\":\"oldpassword123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"mom@example.com\",\"password\":\"newstrongpassword\",\"deviceId\":\"x\"}"))
                .andExpect(status().isOk());

        // the current device stays signed in; the other device is revoked
        mvc.perform(post("/auth/refresh").contentType(APPLICATION_JSON).content("{\"refreshToken\":\"" + currentDeviceRefresh + "\",\"deviceId\":\"dev-current\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/refresh").contentType(APPLICATION_JSON).content("{\"refreshToken\":\"" + otherDeviceRefresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));
    }

    @Test
    void wrongCurrentPassword_returns401() throws Exception {
        mvc.perform(post("/auth/change-password").header("Authorization", "Bearer " + bearer)
                        .contentType(APPLICATION_JSON).content(body("wrongcurrent", "newstrongpassword", "dev-current")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @Test
    void weakNewPassword_returns422() throws Exception {
        mvc.perform(post("/auth/change-password").header("Authorization", "Bearer " + bearer)
                        .contentType(APPLICATION_JSON).content(body("oldpassword123", "short", "dev-current")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("password_too_short"));
    }

    @Test
    void requiresBearer() throws Exception {
        mvc.perform(post("/auth/change-password").contentType(APPLICATION_JSON)
                        .content(body("oldpassword123", "newstrongpassword", "dev-current")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void softLockedAccount_returns429RateLimited() throws Exception {
        // Contract §H: after repeated wrong-current-password attempts the account is soft-locked;
        // the response MUST be 429 rate_limited (not account_locked) so mobile can show the right copy.
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            mvc.perform(post("/auth/change-password").header("Authorization", "Bearer " + bearer)
                            .contentType(APPLICATION_JSON).content(body("WRONG" + i, "newstrongpassword", "dev-current")))
                    .andExpect(status().isUnauthorized()); // wrong current password
        }
        // Now the account is soft-locked — next attempt must yield 429 rate_limited
        mvc.perform(post("/auth/change-password").header("Authorization", "Bearer " + bearer)
                        .contentType(APPLICATION_JSON).content(body("oldpassword123", "newstrongpassword", "dev-current")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"));
    }
}
