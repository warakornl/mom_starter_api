package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AuthGoogleMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @MockBean
    private GoogleIdTokenVerifier verifier;

    private String body() {
        return "{\"idToken\":\"tok\",\"nonce\":\"n\",\"deviceId\":\"dev-1\"}";
    }

    @Test
    void brandNewUser_returns200WithTokens() throws Exception {
        when(verifier.verify(any(), any())).thenReturn(new GoogleIdentity("sub-1", "new@example.com", true));

        mvc.perform(post("/auth/google").contentType(APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        assertThat(users.findByEmail("new@example.com")).isPresent();
    }

    @Test
    void emailCollisionWithoutLink_returns409LinkRequired() throws Exception {
        User existing = new User();
        existing.setEmail("taken@example.com");
        existing.setPasswordHash(encoder.encode("correcthorsebattery"));
        users.save(existing);
        when(verifier.verify(any(), any())).thenReturn(new GoogleIdentity("sub-2", "taken@example.com", true));

        mvc.perform(post("/auth/google").contentType(APPLICATION_JSON).content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("link_required"));
    }

    @Test
    void invalidGoogleToken_returns401() throws Exception {
        when(verifier.verify(any(), any())).thenThrow(new ApiException(401, "google_token_invalid"));

        mvc.perform(post("/auth/google").contentType(APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("google_token_invalid"));
    }

    @Test
    void blankFields_returns422ValidationError() throws Exception {
        mvc.perform(post("/auth/google").contentType(APPLICATION_JSON).content("{\"idToken\":\"\",\"nonce\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }
}
