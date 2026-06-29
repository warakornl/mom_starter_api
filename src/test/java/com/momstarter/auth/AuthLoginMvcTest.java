package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AuthLoginMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private RefreshTokenRepository tokens;
    @Autowired
    private PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        tokens.deleteAll();
        users.deleteAll();
        User u = new User();
        u.setEmail("mom@example.com");
        u.setPasswordHash(encoder.encode("correcthorsebattery"));
        u.setEmailVerified(true);
        users.save(u);
    }

    @Test
    void validLogin_returns200WithTokens() throws Exception {
        mvc.perform(post("/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"mom@example.com\",\"password\":\"correcthorsebattery\",\"deviceId\":\"d1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.accessTokenExpiresIn").isNumber())
                .andExpect(jsonPath("$.refreshTokenExpiresIn").isNumber());
    }

    @Test
    void unknownEmailAndWrongPassword_returnByteIdentical401() throws Exception {
        String unknownBody = mvc.perform(post("/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"ghost@example.com\",\"password\":\"whateverpass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"))
                .andReturn().getResponse().getContentAsString();

        String wrongPwBody = mvc.perform(post("/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"mom@example.com\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"))
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownBody).isEqualTo(wrongPwBody);
    }

    @Test
    void malformedBody_returns400() throws Exception {
        mvc.perform(post("/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
