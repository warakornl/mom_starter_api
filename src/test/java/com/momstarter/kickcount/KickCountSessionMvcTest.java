package com.momstarter.kickcount;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.ConsentChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for {@code GET /v1/kick-count-sessions} (read-only history view).
 *
 * <p>Covers (api-contract §A.3 / functional spec §A.3):
 * <ul>
 *   <li><strong>Empty = 200</strong> (not 404) with {@code items:[]} — critical contract invariant</li>
 *   <li>Single session returned correctly</li>
 *   <li>{@code from}/{@code to} range filter on {@code startedAt}</li>
 *   <li>Tombstoned sessions excluded (only live rows returned)</li>
 *   <li>401 when unauthenticated</li>
 *   <li>Ownership: user A cannot see user B's sessions (IDOR)</li>
 * </ul>
 *
 * <p>Note: per spec A.3 (OQ-K-C resolved: auth-only, no extra consent gate for read),
 * the GET endpoint requires only Bearer JWT + email_verified.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class KickCountSessionMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private KickCountSessionRepository sessions;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    @BeforeEach
    void setup() {
        sessions.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("kick-read@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // Empty collection = 200 with items:[] (NOT 404)
    // -------------------------------------------------------------------------

    @Test
    void get_noSessions_returns200_emptyItems() throws Exception {
        // Contract A.3: empty = 200 {items:[], nextCursor:null} — NOT 404
        mvc.perform(get("/kick-count-sessions")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // Single live session returned
    // -------------------------------------------------------------------------

    @Test
    void get_singleSession_returned() throws Exception {
        KickCountSession s = buildAndSave(userId, LocalDateTime.of(2026, 7, 1, 9, 0),
                LocalDateTime.of(2026, 7, 1, 9, 15), 10, 900);

        mvc.perform(get("/kick-count-sessions")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].id").value(s.getId().toString()))
                .andExpect(jsonPath("$.items[0].movementCount").value(10))
                .andExpect(jsonPath("$.items[0].status").value("completed"));
    }

    // -------------------------------------------------------------------------
    // from / to range filter on startedAt
    // -------------------------------------------------------------------------

    @Test
    void get_rangeFilter_fromAndTo_returnsOnlyMatchingSessions() throws Exception {
        buildAndSave(userId, LocalDateTime.of(2026, 6, 1, 9, 0),
                LocalDateTime.of(2026, 6, 1, 9, 15), 5, 900);   // before range

        KickCountSession inRange = buildAndSave(userId,
                LocalDateTime.of(2026, 7, 15, 9, 0),
                LocalDateTime.of(2026, 7, 15, 9, 15), 10, 900); // in range

        buildAndSave(userId, LocalDateTime.of(2026, 8, 1, 9, 0),
                LocalDateTime.of(2026, 8, 1, 9, 15), 8, 900);   // after range

        mvc.perform(get("/kick-count-sessions")
                        .header("Authorization", "Bearer " + bearer)
                        .param("from", "2026-07-01T00:00")
                        .param("to", "2026-07-31T23:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(inRange.getId().toString()));
    }

    // -------------------------------------------------------------------------
    // Tombstoned sessions excluded
    // -------------------------------------------------------------------------

    @Test
    void get_tombstonedSession_notIncluded() throws Exception {
        KickCountSession live = buildAndSave(userId, LocalDateTime.of(2026, 7, 1, 9, 0),
                LocalDateTime.of(2026, 7, 1, 9, 15), 10, 900);
        KickCountSession tombstoned = buildAndSave(userId, LocalDateTime.of(2026, 7, 2, 9, 0),
                LocalDateTime.of(2026, 7, 2, 9, 15), 7, 800);
        tombstoned.setDeletedAt(Instant.now());
        sessions.save(tombstoned);

        mvc.perform(get("/kick-count-sessions")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(live.getId().toString()));
    }

    // -------------------------------------------------------------------------
    // 401 when unauthenticated
    // -------------------------------------------------------------------------

    @Test
    void get_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/kick-count-sessions"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // IDOR: user A cannot see user B's sessions
    // -------------------------------------------------------------------------

    @Test
    void get_idor_userACannotSeeUserBSessions() throws Exception {
        User userB = new User();
        userB.setEmail("kick-b@example.com");
        userB.setEmailVerified(true);
        userB = users.save(userB);

        // Session belonging to user B
        buildAndSave(userB.getId(), LocalDateTime.of(2026, 7, 1, 9, 0),
                LocalDateTime.of(2026, 7, 1, 9, 15), 8, 600);

        // User A's request — should see empty list
        mvc.perform(get("/kick-count-sessions")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private KickCountSession buildAndSave(UUID ownerId, LocalDateTime startedAt,
                                           LocalDateTime endedAt, int movementCount,
                                           int durationSeconds) {
        KickCountSession s = new KickCountSession();
        s.setId(UUID.randomUUID());
        s.setUserId(ownerId);
        s.setStartedAt(startedAt);
        s.setEndedAt(endedAt);
        s.setMovementCount(movementCount);
        s.setDurationSeconds(durationSeconds);
        s.setTargetCount(10);
        s.setStatus("completed");
        return sessions.save(s);
    }
}
