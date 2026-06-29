package com.momstarter.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AuthVerifyEmailMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JwtDecoder decoder;
    @MockBean
    private VerificationEmailSender sender;

    private String registerAndCaptureToken(String email) throws Exception {
        mvc.perform(post("/auth/register").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correcthorsebattery\"}"))
                .andExpect(status().isAccepted());
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendVerification(eq(email), tokenCaptor.capture());
        return tokenCaptor.getValue();
    }

    @Test
    void verifyEmail_mintsFirstSessionAndMarksAccountVerified() throws Exception {
        String token = registerAndCaptureToken("new@example.com");

        String response = mvc.perform(post("/auth/verify-email").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"deviceId\":\"d1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(users.findByEmail("new@example.com").orElseThrow().isEmailVerified()).isTrue();
        String access = json.readTree(response).get("accessToken").asText();
        assertThat(decoder.decode(access).getClaimAsBoolean("email_verified")).isTrue();
    }

    @Test
    void reusedVerificationToken_returns410() throws Exception {
        String token = registerAndCaptureToken("new@example.com");

        mvc.perform(post("/auth/verify-email").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/auth/verify-email").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("verify_token_invalid"));
    }

    @Test
    void badVerificationToken_returns410() throws Exception {
        mvc.perform(post("/auth/verify-email").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"never-issued\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("verify_token_invalid"))
                // Contract error shape: { code, message, details? } — message must be present and non-blank
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
