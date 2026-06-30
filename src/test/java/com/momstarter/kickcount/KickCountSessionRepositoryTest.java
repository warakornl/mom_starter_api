package com.momstarter.kickcount;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-layer tests for {@link KickCountSession} / {@link KickCountSessionRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 in PostgreSQL mode,
 * {@code application-test.yml}). Mirrors the pattern in {@code SupplyItemRepositoryTest}.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Client-generated id is preserved on save.</li>
 *   <li>Verbatim storage: durationSeconds and gestationalWeekAtStart stored as-is (DRIFT-1).</li>
 *   <li>note_cipher stored as byte[] and echoed back.</li>
 *   <li>Sync-pull keyset query: {@code (updated_at ASC, id ASC)}.</li>
 *   <li>Soft-delete tombstone: row still visible in pull query (propagation).</li>
 *   <li>History query: live rows only, ordered by started_at.</li>
 *   <li>initVersionToOne bumps version 0→1.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class KickCountSessionRepositoryTest {

    @Autowired
    private KickCountSessionRepository sessions;

    @Autowired
    private UserRepository users;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private User savedUser(String email) {
        User u = new User();
        u.setEmail(email);
        return users.save(u);
    }

    private KickCountSession buildSession(UUID userId) {
        KickCountSession s = new KickCountSession();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setStartedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        s.setEndedAt(LocalDateTime.of(2026, 7, 1, 9, 15));
        s.setDurationSeconds(900);
        s.setMovementCount(10);
        s.setTargetCount(10);
        s.setStatus("completed");
        return s;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void clientGeneratedId_preservedOnSave() {
        User user = savedUser("kc-repo-1@example.com");
        UUID clientId = UUID.randomUUID();

        KickCountSession s = buildSession(user.getId());
        s.setId(clientId);

        sessions.save(s);

        KickCountSession loaded = sessions.findById(clientId).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(clientId);
        assertThat(loaded.getUserId()).isEqualTo(user.getId());
    }

    @Test
    void verbatimStorage_durationSeconds_and_gestationalWeek() {
        // DRIFT-1: server stores exactly what client sends; no recompute
        User user = savedUser("kc-repo-2@example.com");
        KickCountSession s = buildSession(user.getId());
        s.setDurationSeconds(4242);
        s.setGestationalWeekAtStart(34);

        sessions.saveAndFlush(s);

        KickCountSession loaded = sessions.findById(s.getId()).orElseThrow();
        assertThat(loaded.getDurationSeconds()).isEqualTo(4242);
        assertThat(loaded.getGestationalWeekAtStart()).isEqualTo(34);
    }

    @Test
    void noteCipher_storedAndEchoedAsBytes() {
        // note_cipher is opaque bytea; server never parses it
        User user = savedUser("kc-repo-3@example.com");
        KickCountSession s = buildSession(user.getId());
        byte[] cipher = new byte[]{0x01, 0x02, 0x03, 0x04};
        s.setNoteCipher(cipher);

        sessions.saveAndFlush(s);

        KickCountSession loaded = sessions.findById(s.getId()).orElseThrow();
        assertThat(loaded.getNoteCipher()).isEqualTo(cipher);
    }

    @Test
    void versionStartsAtZero_initVersionToOne_bumpsToOne() {
        User user = savedUser("kc-repo-4@example.com");
        KickCountSession s = buildSession(user.getId());

        s = sessions.saveAndFlush(s);
        assertThat(s.getVersion()).isEqualTo(0L);

        sessions.initVersionToOne(s.getId());
        sessions.flush();

        KickCountSession reloaded = sessions.findById(s.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void findByUserIdAndIdIn_returnOnlyMatchingOwner() {
        User user1 = savedUser("kc-repo-5a@example.com");
        User user2 = savedUser("kc-repo-5b@example.com");

        KickCountSession s1 = buildSession(user1.getId());
        KickCountSession s2 = buildSession(user2.getId());
        sessions.saveAll(List.of(s1, s2));

        List<KickCountSession> found = sessions.findByUserIdAndIdIn(
                user1.getId(), List.of(s1.getId(), s2.getId()));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(s1.getId());
    }

    @Test
    void findForPull_includesTombstones() {
        User user = savedUser("kc-repo-6@example.com");
        KickCountSession live = buildSession(user.getId());
        KickCountSession tombstoned = buildSession(user.getId());
        tombstoned.setDeletedAt(Instant.now());
        sessions.saveAll(List.of(live, tombstoned));

        List<KickCountSession> result = sessions.findForPull(
                user.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(result).hasSize(2);
    }

    @Test
    void findHistory_excludesTombstones() {
        User user = savedUser("kc-repo-7@example.com");
        KickCountSession live = buildSession(user.getId());
        KickCountSession tombstoned = buildSession(user.getId());
        tombstoned.setDeletedAt(Instant.now());
        sessions.saveAll(List.of(live, tombstoned));

        List<KickCountSession> result = sessions.findHistory(
                user.getId(), null, null, Pageable.ofSize(100));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(live.getId());
    }

    @Test
    void findHistory_rangeFilter_fromAndTo() {
        User user = savedUser("kc-repo-8@example.com");

        KickCountSession early = buildSession(user.getId());
        early.setId(UUID.randomUUID());
        early.setStartedAt(LocalDateTime.of(2026, 6, 1, 9, 0));
        early.setEndedAt(LocalDateTime.of(2026, 6, 1, 9, 10));

        KickCountSession mid = buildSession(user.getId());
        mid.setId(UUID.randomUUID());
        mid.setStartedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        mid.setEndedAt(LocalDateTime.of(2026, 7, 1, 9, 10));

        KickCountSession late = buildSession(user.getId());
        late.setId(UUID.randomUUID());
        late.setStartedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        late.setEndedAt(LocalDateTime.of(2026, 8, 1, 9, 10));

        sessions.saveAll(List.of(early, mid, late));

        LocalDateTime from = LocalDateTime.of(2026, 6, 15, 0, 0);
        LocalDateTime to   = LocalDateTime.of(2026, 7, 31, 23, 59);

        List<KickCountSession> result = sessions.findHistory(
                user.getId(), from, to, Pageable.ofSize(100));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(mid.getId());
    }
}
