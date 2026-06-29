package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
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
class AuthResetPasswordMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private PasswordResetService passwordReset;
    @Autowired
    private AuthService auth;

    private String resetToken;
    private String oldRefreshToken;

    @BeforeEach
    void seed() {
        User u = new User();
        u.setEmail("mom@example.com");
        u.setPasswordHash(encoder.encode("oldpassword123"));
        u.setEmailVerified(true);
        users.save(u);
        oldRefreshToken = auth.login(new LoginRequest("mom@example.com", "oldpassword123", "dev-1"), "ip").refreshToken();
        resetToken = passwordReset.issue(u);
    }

    private String body(String token, String newPassword) {
        return "{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    @Test
    void reset_setsNewPassword_andRevokesEverySession() throws Exception {
        mvc.perform(post("/auth/reset-password").contentType(APPLICATION_JSON).content(body(resetToken, "newstrongpassword")))
                .andExpect(status().isNoContent());

        // old password no longer works, new one does
        mvc.perform(post("/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"mom@example.com\",\"password\":\"oldpassword123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"mom@example.com\",\"password\":\"newstrongpassword\",\"deviceId\":\"d\"}"))
                .andExpect(status().isOk());

        // every pre-reset session is revoked
        mvc.perform(post("/auth/refresh").contentType(APPLICATION_JSON).content("{\"refreshToken\":\"" + oldRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));
    }

    @Test
    void badToken_returns410() throws Exception {
        mvc.perform(post("/auth/reset-password").contentType(APPLICATION_JSON).content(body("never-issued", "newstrongpassword")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("reset_token_invalid"));
    }

    @Test
    void weakNewPassword_returns422() throws Exception {
        mvc.perform(post("/auth/reset-password").contentType(APPLICATION_JSON).content(body(resetToken, "short")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("password_too_short"));
    }

    @Test
    void reusedToken_returns410() throws Exception {
        mvc.perform(post("/auth/reset-password").contentType(APPLICATION_JSON).content(body(resetToken, "newstrongpassword")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/auth/reset-password").contentType(APPLICATION_JSON).content(body(resetToken, "anotherpassword")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("reset_token_invalid"));
    }
}
