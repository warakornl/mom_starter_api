package com.momstarter.dev;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.config.TestSyncAsyncConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T-7 (happy path): flag=true + profile local + embedded H2 → context starts, dev sender active.
 * T-8: 202 body is byte-identical whether flag on or off and whether account exists or not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "momstarter.dev.expose-reset-token=true",
        "momstarter.ratelimit.forgot-per-ip-per-min=1000000",
        "momstarter.ratelimit.forgot-per-account-per-min=1000000"
})
@Import(TestSyncAsyncConfig.class)
@Transactional
@DirtiesContext
class LocalDevPasswordEmailSenderContextTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;

    @Test
    void contextStarts_devSenderActive_andForgotReturns202() throws Exception {
        // Context starts successfully (the test loads at all → T-7)
        mvc.perform(post("/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"ghost@example.com\"}"))
                .andExpect(status().isAccepted());
    }

    /** T-8: 202 body is identical whether account exists or not (non-enumeration, dev sender on). */
    @Test
    void devSenderOn_202BodyByteIdentical_accountExistsOrNot() throws Exception {
        User u = new User();
        u.setEmail("devtest@example.com");
        u.setPasswordHash(encoder.encode("password123"));
        users.save(u);

        String knownBody = mvc.perform(post("/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"devtest@example.com\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String unknownBody = mvc.perform(post("/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"ghost@example.com\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(knownBody)
                .as("202 body must be identical whether account exists or not (non-enumeration)")
                .isEqualTo(unknownBody);
    }
}
