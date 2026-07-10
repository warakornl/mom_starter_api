package com.momstarter.feeding;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link FeedingSession} / {@link FeedingSessionRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 PostgreSQL mode).
 * V20260710000024 (sync-guard trigger) is pg-only and excluded from H2 — INV-ASD-8
 * is asserted via schema-absence (entity has no {@code amountRemainingInOpenContainer} field)
 * rather than via trigger behaviour.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Client-generated id preserved on save.</li>
 *   <li>kind=breastfeed, pump, formula — all valid; unknown kind rejected by DB CHECK.</li>
 *   <li>amountSubUnits: null for breastfeed/pump (enforced by DB cross-field CHECK);
 *       non-negative int accepted for formula; 0 accepted (no-op feed).</li>
 *   <li>volumeMl, durationSeconds: optional nullable fields round-trip.</li>
 *   <li>noteCipher (bytea) round-trips correctly.</li>
 *   <li>startedAt is floating-civil (LocalDateTime, no timezone offset).</li>
 *   <li>Sync-pull keyset queries: findForPull + findForPullAfterCursor.</li>
 *   <li>History queries: findHistory + findHistoryAfterCursor (startedAt keyset).</li>
 *   <li>Tombstone visible in pull query; live-only query via history excludes tombstones.</li>
 *   <li>INV-ASD-4 / INV-ASD-8 / INV-ASD-9: entity has ZERO supply-side linkage fields.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class FeedingSessionRepositoryTest {

    @Autowired
    private FeedingSessionRepository sessions;

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

    private FeedingSession buildSession(UUID userId, String kind) {
        FeedingSession s = new FeedingSession();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setKind(kind);
        s.setStartedAt(LocalDateTime.of(2026, 7, 10, 8, 0));
        return s;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void clientSuppliedId_preserved_onSave() {
        User u = savedUser("fs-id@example.com");
        UUID clientId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        FeedingSession s = buildSession(u.getId(), "breastfeed");
        s.setId(clientId);
        FeedingSession saved = sessions.saveAndFlush(s);

        assertThat(saved.getId()).isEqualTo(clientId);
        assertThat(sessions.findById(clientId)).isPresent();
    }

    @Test
    void breastfeedSession_savesAndRetrieves_syncColumnsPopulated() {
        User u = savedUser("fs-bf@example.com");

        FeedingSession s = buildSession(u.getId(), "breastfeed");
        s.setSide("left");
        s.setDurationSeconds(900);
        s.setClientId(UUID.randomUUID());
        sessions.saveAndFlush(s);

        List<FeedingSession> found = sessions.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(found).hasSize(1);
        FeedingSession f = found.get(0);
        assertThat(f.getKind()).isEqualTo("breastfeed");
        assertThat(f.getSide()).isEqualTo("left");
        assertThat(f.getDurationSeconds()).isEqualTo(900);
        assertThat(f.getAmountSubUnits()).isNull(); // breastfeed: must be null
        assertThat(f.getCreatedAt()).isNotNull();
        assertThat(f.getUpdatedAt()).isNotNull();
        assertThat(f.getDeletedAt()).isNull();
        assertThat(f.getVersion()).isNotNull();
    }

    @Test
    void formulaSession_withAmountSubUnits_roundTrips() {
        User u = savedUser("fs-formula@example.com");

        FeedingSession s = buildSession(u.getId(), "formula");
        s.setAmountSubUnits(4); // 4 scoops
        s.setVolumeMl(new BigDecimal("120.5"));
        sessions.saveAndFlush(s);

        FeedingSession loaded = sessions.findById(s.getId()).orElseThrow();
        assertThat(loaded.getKind()).isEqualTo("formula");
        assertThat(loaded.getAmountSubUnits()).isEqualTo(4);
        assertThat(loaded.getVolumeMl()).isEqualByComparingTo(new BigDecimal("120.5"));
    }

    @Test
    void formulaSession_amountSubUnitsZero_accepted() {
        // 0 scoops = a logged feed with no draw — allowed (data-model §3.14)
        User u = savedUser("fs-zero@example.com");

        FeedingSession s = buildSession(u.getId(), "formula");
        s.setAmountSubUnits(0);
        sessions.saveAndFlush(s);

        FeedingSession loaded = sessions.findById(s.getId()).orElseThrow();
        assertThat(loaded.getAmountSubUnits()).isEqualTo(0);
    }

    @Test
    void breastfeedSession_amountSubUnitsNonNull_rejectedByDbCrossFieldCheck() {
        // DB CHECK (kind='formula' OR amount_sub_units IS NULL) — only formula may carry this
        User u = savedUser("fs-asd-check@example.com");

        FeedingSession s = buildSession(u.getId(), "breastfeed");
        s.setAmountSubUnits(3); // violates cross-field CHECK constraint

        assertThatThrownBy(() -> {
            sessions.save(s);
            sessions.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pumpSession_amountSubUnitsNonNull_rejectedByDbCrossFieldCheck() {
        User u = savedUser("fs-pump-asd@example.com");

        FeedingSession s = buildSession(u.getId(), "pump");
        s.setAmountSubUnits(2); // violates cross-field CHECK

        assertThatThrownBy(() -> {
            sessions.save(s);
            sessions.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownKind_rejectedByDbConstraint() {
        User u = savedUser("fs-kind@example.com");

        FeedingSession s = buildSession(u.getId(), "bottle"); // invalid kind

        assertThatThrownBy(() -> {
            sessions.save(s);
            sessions.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void noteCipher_byteArray_roundTrips() {
        User u = savedUser("fs-note@example.com");
        byte[] cipher = new byte[]{1, 2, 3, 42};

        FeedingSession s = buildSession(u.getId(), "breastfeed");
        s.setNoteCipher(cipher);
        sessions.saveAndFlush(s);

        FeedingSession loaded = sessions.findById(s.getId()).orElseThrow();
        assertThat(loaded.getNoteCipher()).isEqualTo(cipher);
    }

    @Test
    void startedAt_floatingCivil_noTimezoneShift() {
        // FLAG-1: startedAt is stored as timestamp WITHOUT TIME ZONE — never UTC-normalised
        User u = savedUser("fs-startAt@example.com");
        LocalDateTime civil = LocalDateTime.of(2026, 6, 15, 3, 30);

        FeedingSession s = buildSession(u.getId(), "formula");
        s.setStartedAt(civil);
        sessions.saveAndFlush(s);

        FeedingSession loaded = sessions.findById(s.getId()).orElseThrow();
        assertThat(loaded.getStartedAt()).isEqualTo(civil);
    }

    @Test
    void tombstone_visibleInPullQuery_excludedFromHistoryQuery() {
        User u = savedUser("fs-tombstone@example.com");

        FeedingSession s = buildSession(u.getId(), "breastfeed");
        sessions.saveAndFlush(s);
        s.setDeletedAt(Instant.now());
        sessions.saveAndFlush(s);

        // Pull includes tombstones (other devices must learn of the deletion)
        List<FeedingSession> pull = sessions.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(pull).hasSize(1);
        assertThat(pull.get(0).getDeletedAt()).isNotNull();

        // History query excludes tombstones (live rows only)
        List<FeedingSession> history = sessions.findHistory(
                u.getId(), null, null, Pageable.unpaged());
        assertThat(history).isEmpty();
    }

    @Test
    void cursorContinuation_resumesAfterGivenPosition() {
        User u = savedUser("fs-cursor@example.com");

        FeedingSession a = buildSession(u.getId(), "breastfeed");
        a = sessions.saveAndFlush(a);

        FeedingSession b = buildSession(u.getId(), "pump");
        b.setStartedAt(LocalDateTime.of(2026, 7, 11, 9, 0));
        b = sessions.saveAndFlush(b);

        // Update a → a has newer updatedAt
        a.setSide("right");
        a = sessions.saveAndFlush(a);

        // Capture IDs into effectively-final locals (a and b are reassigned above).
        final UUID aId = a.getId();
        final UUID bId = b.getId();

        List<FeedingSession> afterCursor = sessions.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                b.getUpdatedAt(), bId,
                Pageable.ofSize(100));

        assertThat(afterCursor).anyMatch(s -> s.getId().equals(aId));
        assertThat(afterCursor).noneMatch(s -> s.getId().equals(bId));
    }

    @Test
    void historyQuery_filtersByStartedAt_excludesTombstones() {
        User u = savedUser("fs-history@example.com");

        FeedingSession morning = buildSession(u.getId(), "breastfeed");
        morning.setStartedAt(LocalDateTime.of(2026, 7, 10, 7, 0));
        sessions.saveAndFlush(morning);

        FeedingSession noon = buildSession(u.getId(), "pump");
        noon.setStartedAt(LocalDateTime.of(2026, 7, 10, 12, 0));
        sessions.saveAndFlush(noon);

        // Only morning session is in range
        List<FeedingSession> found = sessions.findHistory(
                u.getId(),
                LocalDateTime.of(2026, 7, 10, 6, 0),
                LocalDateTime.of(2026, 7, 10, 10, 0),
                Pageable.unpaged());

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getStartedAt()).isEqualTo(morning.getStartedAt());
    }

    @Test
    void pullQuery_isolatesResultsByUserId() {
        User alice = savedUser("alice-fs@example.com");
        User bob = savedUser("bob-fs@example.com");

        sessions.saveAndFlush(buildSession(alice.getId(), "breastfeed"));
        sessions.saveAndFlush(buildSession(bob.getId(), "formula"));

        List<FeedingSession> aliceSessions = sessions.findForPull(alice.getId(), Instant.EPOCH, Pageable.unpaged());
        List<FeedingSession> bobSessions = sessions.findForPull(bob.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(aliceSessions).hasSize(1);
        assertThat(aliceSessions.get(0).getUserId()).isEqualTo(alice.getId());
        assertThat(bobSessions).hasSize(1);
        assertThat(bobSessions.get(0).getUserId()).isEqualTo(bob.getId());
    }

    /**
     * INV-ASD-4 / INV-ASD-8 / INV-ASD-9: the {@link FeedingSession} entity carries ZERO
     * supply-side linkage. No {@code supplyItemId}, no {@code fedAt}, no
     * {@code usesRemainingInOpenContainer}, no {@code amountRemainingInContainer} field exists
     * anywhere on this entity.
     */
    @Test
    void inv_asd4_noSupplySideLinkageFieldsOnEntity() {
        var fieldNames = java.util.Arrays.stream(FeedingSession.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList();

        // INV-ASD-4: no supply linkage
        assertThat(fieldNames).doesNotContain("supplyItemId", "fedAt", "perFeedAmount");
        // INV-ASD-8: no mobile-local open-container state
        assertThat(fieldNames).doesNotContain(
                "usesRemainingInOpenContainer", "amountRemainingInContainer");
        // INV-ASD-9: no activity linkage from supply side
        assertThat(fieldNames).doesNotContain("activityType");
    }
}
