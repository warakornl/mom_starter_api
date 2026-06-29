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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class AuthSessionsListMvcTest {

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

    @BeforeEach
    void seed() {
        User u = new User();
        u.setEmail("mom@example.com");
        u.setPasswordHash(encoder.encode("correcthorsebattery"));
        u.setEmailVerified(true);
        users.save(u);
        bearer = jwt.issueAccessToken(u.getId(), true);
        auth.login(new LoginRequest("mom@example.com", "correcthorsebattery", "device-1"), "ip");
        auth.login(new LoginRequest("mom@example.com", "correcthorsebattery", "device-2"), "ip");
    }

    @Test
    void sessionsRequireBearer() throws Exception {
        mvc.perform(get("/auth/sessions")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsOneSessionPerActiveDevice() throws Exception {
        // Contract N4/N5: list endpoints return Page<T> = { items: [...], nextCursor? }  — NOT a bare array
        mvc.perform(get("/auth/sessions").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].deviceId", containsInAnyOrder("device-1", "device-2")));
    }
}
