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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AuthResendVerificationMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @MockBean
    private VerificationEmailSender sender;

    private String resend(String email) throws Exception {
        return mvc.perform(post("/auth/resend-verification").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private void seed(String email, boolean verified) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode("correcthorsebattery"));
        u.setEmailVerified(verified);
        users.save(u);
    }

    @Test
    void unknownEmail_returns202_andSendsNothing() throws Exception {
        resend("ghost@example.com");
        verify(sender, never()).sendVerification(anyString(), anyString());
    }

    @Test
    void knownUnverified_returns202_andResends() throws Exception {
        seed("mom@example.com", false);
        resend("mom@example.com");
        verify(sender).sendVerification(eq("mom@example.com"), anyString());
    }

    @Test
    void knownVerified_returns202_andSendsNothing() throws Exception {
        seed("mom@example.com", true);
        resend("mom@example.com");
        verify(sender, never()).sendVerification(anyString(), anyString());
    }

    @Test
    void allCasesReturnByteIdenticalBody() throws Exception {
        seed("verified@example.com", true);
        seed("unverified@example.com", false);

        String unknownBody = resend("ghost@example.com");
        String verifiedBody = resend("verified@example.com");
        String unverifiedBody = resend("unverified@example.com");

        assertThat(unknownBody).isEqualTo(verifiedBody);
        assertThat(verifiedBody).isEqualTo(unverifiedBody);
    }
}
