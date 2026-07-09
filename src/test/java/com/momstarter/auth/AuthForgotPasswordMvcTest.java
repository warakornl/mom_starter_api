package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.config.TestSyncAsyncConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
@Import(TestSyncAsyncConfig.class)
@Transactional
class AuthForgotPasswordMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @MockBean
    private PasswordEmailSender sender;

    private String forgot(String email) throws Exception {
        return mvc.perform(post("/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void unknownEmail_returns202_andSendsNothing() throws Exception {
        forgot("ghost@example.com");
        // SyncTaskExecutor makes @Async run in the calling thread: verify immediately
        verify(sender, never()).sendPasswordReset(anyString(), anyString());
    }

    @Test
    void knownEmail_returns202_andSendsResetLink() throws Exception {
        User u = new User();
        u.setEmail("mom@example.com");
        u.setPasswordHash(encoder.encode("correcthorsebattery"));
        users.save(u);

        forgot("mom@example.com");

        // SyncTaskExecutor: dispatch runs synchronously so verify is immediate
        verify(sender).sendPasswordReset(eq("mom@example.com"), anyString());
    }

    @Test
    void unknownAndKnown_returnByteIdenticalBody() throws Exception {
        User u = new User();
        u.setEmail("known@example.com");
        u.setPasswordHash(encoder.encode("correcthorsebattery"));
        users.save(u);

        String unknownBody = forgot("ghost@example.com");
        String knownBody = forgot("known@example.com");

        assertThat(unknownBody).isEqualTo(knownBody);
    }
}
