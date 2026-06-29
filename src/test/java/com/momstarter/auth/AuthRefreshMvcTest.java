package com.momstarter.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class AuthRefreshMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private AuthService auth;
    @Autowired
    private ObjectMapper json;

    private String initialRefresh;

    @BeforeEach
    void seed() {
        User u = new User();
        u.setEmail("mom@example.com");
        u.setPasswordHash(encoder.encode("correcthorsebattery"));
        u.setEmailVerified(true);
        users.save(u);
        initialRefresh = auth.login(new LoginRequest("mom@example.com", "correcthorsebattery", "d1"), "ip").refreshToken();
    }

    private String body(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\",\"deviceId\":\"d1\"}";
    }

    @Test
    void refreshRotatesAndReturnsNewTokens() throws Exception {
        mvc.perform(post("/auth/refresh").contentType(APPLICATION_JSON).content(body(initialRefresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void reuseOfRotatedToken_burnsFamily() throws Exception {
        // first legit rotation (via the endpoint), capture the new token
        String response = mvc.perform(post("/auth/refresh").contentType(APPLICATION_JSON).content(body(initialRefresh)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rotated = json.readTree(response).get("refreshToken").asText();

        // replaying the original (now-rotated) token is theft -> token_reuse_detected
        mvc.perform(post("/auth/refresh").contentType(APPLICATION_JSON).content(body(initialRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_reuse_detected"));

        // the legit latest token is dead too — the whole family was revoked
        mvc.perform(post("/auth/refresh").contentType(APPLICATION_JSON).content(body(rotated)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));
    }

    @Test
    void garbageToken_returns401InvalidToken() throws Exception {
        mvc.perform(post("/auth/refresh").contentType(APPLICATION_JSON).content(body("not-a-real-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));
    }
}
