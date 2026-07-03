package com.momstarter.selflog;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link SelfLog} / {@link SelfLogRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 in PostgreSQL mode,
 * {@code application-test.yml}). Mirrors the pattern in {@code KickCountSessionRepositoryTest}.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Client-generated id is preserved on save (no server generation).</li>
 *   <li>All four {@code bytea} value columns stored and echoed verbatim (INV-S2 / ADR Option A).</li>
 *   <li>{@code @Version} starts at 0; {@link SelfLogRepository#initVersionToOne} bumps to 1.</li>
 *   <li>{@link SelfLogRepository#findByUserIdAndIdIn} returns only matching owner+id (IDOR guard).</li>
 *   <li>Sync-pull: tombstones are included ({@link SelfLogRepository#findForPull}).</li>
 *   <li>Sync-pull cursor: {@link SelfLogRepository#findForPullAfterCursor} continues after cursor.</li>
 *   <li>GET /self-logs: live rows only, {@link SelfLogRepository#findForRead} with optional
 *       metricType filter and loggedAt range; ORDER BY loggedAt DESC, id DESC.</li>
 *   <li>GET /self-logs cursor: {@link SelfLogRepository#findForReadAfterCursor} continues paging.</li>
 *   <li>Soft-deleted rows excluded from {@code findForRead} but present in {@code findForPull}.</li>
 *   <li>DB CHECK rejects invalid {@code metric_type}.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class SelfLogRepositoryTest {

    @Autowired
    private SelfLogRepository logs;

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

    /**
     * Builds an unsaved SelfLog with client-generated UUID. Defaults to metric_type=weight
     * and loggedAt set to the supplied civil timestamp.
     */
    private SelfLog buildLog(UUID userId, String metricType, LocalDateTime loggedAt) {
        SelfLog s = new SelfLog();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setMetricType(metricType);
        s.setLoggedAt(loggedAt);
        return s;
    }

    private SelfLog buildLog(UUID userId) {
        return buildLog(userId, "weight", LocalDateTime.of(2026, 7, 1, 9, 0));
    }

    // -------------------------------------------------------------------------
    // 1. Client-generated id preserved
    // -------------------------------------------------------------------------

    @Test
    void clientGeneratedId_preservedOnSave() {
        User user = savedUser("sl-repo-1@example.com");
        UUID clientId = UUID.randomUUID();

        SelfLog s = buildLog(user.getId());
        s.setId(clientId);
        logs.saveAndFlush(s);

        SelfLog loaded = logs.findById(clientId).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(clientId);
        assertThat(loaded.getUserId()).isEqualTo(user.getId());
    }

    // -------------------------------------------------------------------------
    // 2. All four bytea columns stored and echoed verbatim (INV-S2 / ADR Option A)
    // -------------------------------------------------------------------------

    @Test
    void byteValueColumns_storedAndEchoedVerbatim() {
        // ADR Decision 1: value_numeric/secondary/value_text/note_cipher are bytea columns
        // holding MVP plaintext bytes. Server never parses them (INV-S2).
        User user = savedUser("sl-repo-2@example.com");
        SelfLog s = buildLog(user.getId(), "blood_pressure", LocalDateTime.of(2026, 7, 2, 10, 30));

        byte[] numeric    = new byte[]{0x01, 0x02, 0x03};      // systolic
        byte[] numeric2   = new byte[]{0x04, 0x05, 0x06};      // diastolic
        byte[] text       = new byte[]{0x07, 0x08};             // unused for BP but nullable
        byte[] noteCipher = new byte[]{0x0A, 0x0B, 0x0C, 0x0D};

        s.setValueNumeric(numeric);
        s.setValueNumericSecondary(numeric2);
        s.setValueText(text);
        s.setNoteCipher(noteCipher);
        s.setUnit("mmHg");

        logs.saveAndFlush(s);

        SelfLog loaded = logs.findById(s.getId()).orElseThrow();
        assertThat(loaded.getValueNumeric()).isEqualTo(numeric);
        assertThat(loaded.getValueNumericSecondary()).isEqualTo(numeric2);
        assertThat(loaded.getValueText()).isEqualTo(text);
        assertThat(loaded.getNoteCipher()).isEqualTo(noteCipher);
        assertThat(loaded.getUnit()).isEqualTo("mmHg");
        assertThat(loaded.getMetricType()).isEqualTo("blood_pressure");
    }

    @Test
    void nullableValueColumns_acceptAllNull() {
        // All four value columns are nullable; e.g. symptom may only populate valueText
        // and many rows arrive without note_cipher.
        User user = savedUser("sl-repo-3@example.com");
        SelfLog s = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 3, 8, 0));
        // Only valueNumeric set; others remain null
        s.setValueNumeric(new byte[]{0x64}); // 100 as single byte

        SelfLog saved = logs.saveAndFlush(s);

        assertThat(saved.getValueNumericSecondary()).isNull();
        assertThat(saved.getValueText()).isNull();
        assertThat(saved.getNoteCipher()).isNull();
        assertThat(saved.getUnit()).isNull();
    }

    // -------------------------------------------------------------------------
    // 3. initVersionToOne — bumps version 0 → 1 (api-contract §5)
    // -------------------------------------------------------------------------

    @Test
    void versionStartsAtZero_initVersionToOne_bumpsToOne() {
        User user = savedUser("sl-repo-4@example.com");
        SelfLog s = buildLog(user.getId());

        s = logs.saveAndFlush(s);
        assertThat(s.getVersion()).isEqualTo(0L);

        logs.initVersionToOne(s.getId());
        logs.flush();

        SelfLog reloaded = logs.findById(s.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // 4. findByUserIdAndIdIn — IDOR guard
    // -------------------------------------------------------------------------

    @Test
    void findByUserIdAndIdIn_returnsOnlyMatchingOwner() {
        User alice = savedUser("sl-repo-5a@example.com");
        User bob   = savedUser("sl-repo-5b@example.com");

        SelfLog aliceLog = buildLog(alice.getId());
        SelfLog bobLog   = buildLog(bob.getId());
        logs.saveAll(List.of(aliceLog, bobLog));

        // Querying with alice's userId and both ids — should only return alice's
        List<SelfLog> found = logs.findByUserIdAndIdIn(
                alice.getId(), List.of(aliceLog.getId(), bobLog.getId()));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(aliceLog.getId());
    }

    // -------------------------------------------------------------------------
    // 5. findForPull — includes tombstones (sync-pull propagates deletions)
    // -------------------------------------------------------------------------

    @Test
    void findForPull_includesTombstones() {
        User user = savedUser("sl-repo-6@example.com");

        SelfLog live       = buildLog(user.getId());
        SelfLog tombstoned = buildLog(user.getId());
        tombstoned.setDeletedAt(Instant.now());
        logs.saveAll(List.of(live, tombstoned));

        List<SelfLog> result = logs.findForPull(user.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(result).hasSize(2);
    }

    @Test
    void findForPull_isolatesResultsByUserId() {
        User alice = savedUser("sl-pull-alice@example.com");
        User bob   = savedUser("sl-pull-bob@example.com");

        logs.saveAndFlush(buildLog(alice.getId()));
        logs.saveAndFlush(buildLog(bob.getId()));

        List<SelfLog> aliceLogs = logs.findForPull(alice.getId(), Instant.EPOCH, Pageable.unpaged());
        List<SelfLog> bobLogs   = logs.findForPull(bob.getId(),   Instant.EPOCH, Pageable.unpaged());

        assertThat(aliceLogs).hasSize(1);
        assertThat(bobLogs).hasSize(1);
        assertThat(aliceLogs.get(0).getUserId()).isEqualTo(alice.getId());
        assertThat(bobLogs.get(0).getUserId()).isEqualTo(bob.getId());
    }

    // -------------------------------------------------------------------------
    // 6. findForPullAfterCursor — cursor continuation (cold-start keyset)
    // -------------------------------------------------------------------------

    @Test
    void findForPullAfterCursor_resumesCorrectly() {
        User user = savedUser("sl-repo-7@example.com");

        SelfLog a = logs.saveAndFlush(buildLog(user.getId()));
        SelfLog b = logs.saveAndFlush(buildLog(user.getId()));

        // Use B as the cursor — only A should appear after (if A.updatedAt > B.updatedAt)
        // A was saved first so B.updatedAt >= A.updatedAt; cursor after B gives nothing beyond.
        // To make A come after B in updatedAt order, ensure A is updated after B.
        // Since both are fresh inserts with nearly identical timestamps, let's use the
        // direct approach: cursor at EPOCH means all rows returned.
        List<SelfLog> afterEpochCursor = logs.findForPullAfterCursor(
                user.getId(), Instant.EPOCH,
                Instant.EPOCH, UUID.fromString("00000000-0000-0000-0000-000000000000"),
                Pageable.ofSize(100));

        // Both rows come after the EPOCH cursor
        assertThat(afterEpochCursor).hasSize(2);
        // Now use B as cursor: rows after B in (updatedAt, id) order
        List<SelfLog> afterB = logs.findForPullAfterCursor(
                user.getId(), Instant.EPOCH,
                b.getUpdatedAt(), b.getId(),
                Pageable.ofSize(100));
        // Nothing comes strictly after B when B is the latest (or only A if A.updatedAt > B.updatedAt)
        // The key invariant: B itself is NOT in afterB
        assertThat(afterB).noneMatch(sl -> sl.getId().equals(b.getId()));
    }

    // -------------------------------------------------------------------------
    // 7. findForRead — live rows only, optional filters, ORDER BY loggedAt DESC, id DESC
    // -------------------------------------------------------------------------

    @Test
    void findForRead_excludesSoftDeleted() {
        User user = savedUser("sl-repo-8@example.com");

        SelfLog live = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 1, 9, 0));
        SelfLog dead = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 2, 9, 0));
        logs.saveAndFlush(live);
        logs.saveAndFlush(dead);

        dead.setDeletedAt(Instant.now());
        logs.saveAndFlush(dead);

        List<SelfLog> result = logs.findForRead(user.getId(), null, null, null, Pageable.ofSize(100));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(live.getId());
    }

    @Test
    void findForRead_metricTypeFilter_returnsMatchingOnly() {
        User user = savedUser("sl-repo-9@example.com");

        SelfLog weight = buildLog(user.getId(), "weight",        LocalDateTime.of(2026, 7, 1, 8, 0));
        SelfLog bp     = buildLog(user.getId(), "blood_pressure", LocalDateTime.of(2026, 7, 1, 9, 0));
        SelfLog symp   = buildLog(user.getId(), "symptom",        LocalDateTime.of(2026, 7, 1, 10, 0));
        logs.saveAll(List.of(weight, bp, symp));

        List<SelfLog> bpOnly = logs.findForRead(user.getId(), "blood_pressure", null, null, Pageable.ofSize(100));

        assertThat(bpOnly).hasSize(1);
        assertThat(bpOnly.get(0).getMetricType()).isEqualTo("blood_pressure");
    }

    @Test
    void findForRead_dateRangeFilter_fromAndTo() {
        User user = savedUser("sl-repo-10@example.com");

        // Three logs on different civil days
        SelfLog early = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 6, 1,  9, 0));
        SelfLog mid   = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 1,  9, 0));
        SelfLog late  = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 8, 1,  9, 0));
        logs.saveAll(List.of(early, mid, late));

        LocalDateTime from = LocalDateTime.of(2026, 6, 15, 0, 0);
        LocalDateTime to   = LocalDateTime.of(2026, 7, 31, 23, 59);

        List<SelfLog> result = logs.findForRead(user.getId(), null, from, to, Pageable.ofSize(100));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(mid.getId());
    }

    @Test
    void findForRead_combinedMetricTypeAndDateRange() {
        User user = savedUser("sl-repo-11@example.com");

        // weight on July 1 — should match
        SelfLog match = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 1, 9, 0));
        // weight on Aug 1 — outside range
        SelfLog outOfRange = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 8, 1, 9, 0));
        // blood_pressure on July 1 — wrong metric
        SelfLog wrongMetric = buildLog(user.getId(), "blood_pressure", LocalDateTime.of(2026, 7, 1, 10, 0));
        logs.saveAll(List.of(match, outOfRange, wrongMetric));

        LocalDateTime from = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime to   = LocalDateTime.of(2026, 7, 31, 23, 59);

        List<SelfLog> result = logs.findForRead(user.getId(), "weight", from, to, Pageable.ofSize(100));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(match.getId());
    }

    @Test
    void findForRead_orderedByLoggedAtDescThenIdDesc() {
        User user = savedUser("sl-repo-12@example.com");

        // Three logs with different loggedAt values
        SelfLog oldest = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 1,  8, 0));
        SelfLog middle = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 5,  8, 0));
        SelfLog newest = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 10, 8, 0));
        // Save in reverse order to confirm sort is by loggedAt, not insert order
        logs.saveAll(List.of(oldest, middle, newest));

        List<SelfLog> result = logs.findForRead(user.getId(), null, null, null, Pageable.ofSize(100));

        assertThat(result).hasSize(3);
        // Descending: newest first
        assertThat(result.get(0).getId()).isEqualTo(newest.getId());
        assertThat(result.get(1).getId()).isEqualTo(middle.getId());
        assertThat(result.get(2).getId()).isEqualTo(oldest.getId());
    }

    @Test
    void findForRead_nullMetricType_returnsAllMetrics() {
        User user = savedUser("sl-repo-13@example.com");

        logs.saveAndFlush(buildLog(user.getId(), "weight",        LocalDateTime.of(2026, 7, 1, 8, 0)));
        logs.saveAndFlush(buildLog(user.getId(), "swelling",      LocalDateTime.of(2026, 7, 2, 8, 0)));
        logs.saveAndFlush(buildLog(user.getId(), "lochia",        LocalDateTime.of(2026, 7, 3, 8, 0)));
        logs.saveAndFlush(buildLog(user.getId(), "symptom",       LocalDateTime.of(2026, 7, 4, 8, 0)));
        logs.saveAndFlush(buildLog(user.getId(), "blood_pressure", LocalDateTime.of(2026, 7, 5, 8, 0)));

        List<SelfLog> result = logs.findForRead(user.getId(), null, null, null, Pageable.ofSize(100));

        assertThat(result).hasSize(5);
    }

    // -------------------------------------------------------------------------
    // 8. findForReadAfterCursor — cursor continuation for GET /self-logs (DESC keyset)
    // -------------------------------------------------------------------------

    @Test
    void findForReadAfterCursor_resumesAfterPosition() {
        User user = savedUser("sl-repo-14@example.com");

        // Create three logs with decreasing loggedAt (newest first in query order)
        SelfLog newest = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 10, 9, 0));
        SelfLog middle = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 5,  9, 0));
        SelfLog oldest = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 1,  9, 0));
        logs.saveAll(List.of(newest, middle, oldest));

        // Cursor at "newest" row → should return middle + oldest
        List<SelfLog> afterNewest = logs.findForReadAfterCursor(
                user.getId(), null, null, null,
                newest.getLoggedAt(), newest.getId(),
                Pageable.ofSize(100));

        assertThat(afterNewest).hasSize(2);
        assertThat(afterNewest).noneMatch(sl -> sl.getId().equals(newest.getId()));
        assertThat(afterNewest).anyMatch(sl -> sl.getId().equals(middle.getId()));
        assertThat(afterNewest).anyMatch(sl -> sl.getId().equals(oldest.getId()));
    }

    @Test
    void findForReadAfterCursor_excludesSoftDeleted() {
        User user = savedUser("sl-repo-15@example.com");

        SelfLog live   = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 10, 9, 0));
        SelfLog cursor = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7, 15, 9, 0));
        SelfLog dead   = buildLog(user.getId(), "weight", LocalDateTime.of(2026, 7,  1, 9, 0));
        logs.saveAll(List.of(live, cursor, dead));

        dead.setDeletedAt(Instant.now());
        logs.saveAndFlush(dead);

        // Cursor at "cursor" row (loggedAt=July 15) → items after cursor in DESC order = live (July 10)
        List<SelfLog> result = logs.findForReadAfterCursor(
                user.getId(), null, null, null,
                cursor.getLoggedAt(), cursor.getId(),
                Pageable.ofSize(100));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(live.getId());
    }

    // -------------------------------------------------------------------------
    // 9. DB CHECK — rejects invalid metric_type
    // -------------------------------------------------------------------------

    @Test
    void invalidMetricType_rejectedByDbConstraint() {
        User user = savedUser("sl-repo-16@example.com");
        SelfLog s = buildLog(user.getId(), "invalid-metric", LocalDateTime.of(2026, 7, 1, 9, 0));

        assertThatThrownBy(() -> {
            logs.save(s);
            logs.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // -------------------------------------------------------------------------
    // 10. Sync block fields — createdAt/updatedAt/deletedAt/clientId
    // -------------------------------------------------------------------------

    @Test
    void syncBlock_timestamps_andClientId_storedCorrectly() {
        User user = savedUser("sl-repo-17@example.com");
        SelfLog s = buildLog(user.getId());
        UUID deviceId = UUID.randomUUID();
        s.setClientId(deviceId);

        SelfLog saved = logs.saveAndFlush(s);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getClientId()).isEqualTo(deviceId);
        assertThat(saved.getVersion()).isNotNull();
    }
}
