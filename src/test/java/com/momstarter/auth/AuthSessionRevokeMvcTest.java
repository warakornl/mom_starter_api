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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AuthSessionRevokeMvcTest {

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
    private String refreshDevice1;
    private String refreshDevice2;

    @BeforeEach
    void seed() {
        User u = new User();
        u.setEmail("mom@example.com");
        u.setPasswordHash(encoder.encode("correcthorsebattery"));
        u.setEmailVerified(true);
        users.save(u);
        bearer = jwt.issueAccessToken(u.getId(), true);
        refreshDevice1 = auth.login(new LoginRequest("mom@example.com", "correcthorsebattery", "device-1"), "ip").refreshToken();
        refreshDevice2 = auth.login(new LoginRequest("mom@example.com", "correcthorsebattery", "device-2"), "ip").refreshToken();
    }

    private String refreshBody(String token) {
        return "{\"refreshToken\":\"" + token + "\"}";
    }

    @Test
    void revokeRequiresBearer() throws Exception {
        mvc.perform(delete("/auth/sessions/device-1")).andExpect(status().isUnauthorized());
    }

    @Test
    void deleteSessionByDeviceIdRevokesOnlyThatDevice() throws Exception {
        mvc.perform(delete("/auth/sessions/device-1").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNoContent());

        mvc.perform(post("/auth/refresh").contentType(APPLICATION_JSON).content(refreshBody(refreshDevice1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));

        mvc.perform(post("/auth/refresh").contentType(APPLICATION_JSON).content(refreshBody(refreshDevice2)))
                .andExpect(status().isOk());
    }

    @Test
    void deleteAllSessionsRevokesEveryDevice() throws Exception {
        mvc.perform(delete("/auth/sessions").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNoContent());

        mvc.perform(post("/auth/refresh").contentType(APPLICATION_JSON).content(refreshBody(refreshDevice2)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));
    }
}
