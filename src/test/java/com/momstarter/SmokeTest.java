package com.momstarter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 0 / Task 0.1 — proves the Spring Boot application context boots.
 * Uses the `test` profile (in-memory H2, Flyway off) so no real database is needed.
 */
@SpringBootTest
@ActiveProfiles("test")
class SmokeTest {

    @Test
    void contextLoads() {
        // If the Spring context fails to start, this test fails.
    }
}
