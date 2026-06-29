package com.momstarter.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the actuator health endpoint is:
 *   - reachable without authentication (public — Prometheus / load-balancer probe path)
 *   - returns HTTP 200
 *   - reports status UP
 *
 * Uses the test profile (H2 in-memory, Flyway off) so no real DB is required.
 * The path /actuator/health is relative to the servlet context-path (/v1), i.e.,
 * the full URL in Docker is http://api:8080/v1/actuator/health.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorHealthTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void actuatorHealth_returnsOkAndStatusUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void actuatorHealth_isPublicNoAuthRequired() throws Exception {
        // No Authorization header — must still return 200, not 401/403
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
