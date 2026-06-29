package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
class AuthRegisterMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @MockBean
    private VerificationEmailSender sender;

    private String register(String email, String password) throws Exception {
        return mvc.perform(post("/auth/register").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void newEmail_returns202_createsUnverifiedUser_sendsVerification() throws Exception {
        mvc.perform(post("/auth/register").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"password\":\"correcthorsebattery\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("verification_pending"));

        User u = users.findByEmail("new@example.com").orElseThrow();
        assertThat(u.isEmailVerified()).isFalse();
        assertThat(u.getPasswordHash()).isNotBlank();
        verify(sender).sendVerification(eq("new@example.com"), anyString());
        verify(sender, never()).sendAlreadyRegisteredNotice(anyString());
    }

    @Test
    void existingEmail_returns202_createsNoNewUser_leavesOriginalUntouched_sendsNotice() throws Exception {
        User existing = new User();
        existing.setEmail("mom@example.com");
        existing.setPasswordHash(encoder.encode("originalpassword"));
        existing.setEmailVerified(true);
        users.save(existing);
        String originalHash = existing.getPasswordHash();
        long before = users.count();

        mvc.perform(post("/auth/register").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"mom@example.com\",\"password\":\"correcthorsebattery\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("verification_pending"));

        assertThat(users.count()).isEqualTo(before);
        assertThat(users.findByEmail("mom@example.com").orElseThrow().getPasswordHash()).isEqualTo(originalHash);
        verify(sender).sendAlreadyRegisteredNotice("mom@example.com");
        verify(sender, never()).sendVerification(anyString(), anyString());
    }

    @Test
    void newAndExistingEmail_returnByteIdenticalBody() throws Exception {
        User existing = new User();
        existing.setEmail("taken@example.com");
        existing.setPasswordHash(encoder.encode("originalpassword"));
        users.save(existing);

        String freshBody = register("fresh@example.com", "correcthorsebattery");
        String collisionBody = register("taken@example.com", "correcthorsebattery");

        assertThat(freshBody).isEqualTo(collisionBody);
    }

    @Test
    void weakPassword_returns422_beforeAnyExistenceCheck() throws Exception {
        mvc.perform(post("/auth/register").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\",\"password\":\"short\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("password_too_short"));
    }
}
