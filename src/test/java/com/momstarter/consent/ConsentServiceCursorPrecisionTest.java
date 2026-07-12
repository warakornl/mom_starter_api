package com.momstarter.consent;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.consent.dto.ConsentListResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic repro for the {@code GET /account/consents} cursor sub-millisecond
 * truncation bug (found while systematic-debugging an intermittent full-suite flake
 * reported as {@code ConsentMvcTest.get_cursorPagination_limit1_nextCursorPresent}
 * returning {@code items.length() == 0} on the SECOND (cursor-continuation) page).
 *
 * <h2>Root cause</h2>
 * <p>{@code ConsentRecord.grantedAt} is a Java {@link Instant} (microsecond-resolution
 * on this JVM/clock — confirmed: {@code Instant.now()} routinely differs at the
 * microsecond level between two calls a few nanoseconds apart, e.g.
 * {@code ...504019Z} then {@code ...504066Z}, both truncating to the SAME
 * {@code epochMilli}). {@code ConsentService#encodeCursor}/{@code #decodeCursor}
 * round-trip the cursor through {@code Instant.toEpochMilli()} /
 * {@code Instant.ofEpochMilli()} — a LOSSY millisecond truncation that always zeroes
 * the sub-millisecond remainder.
 *
 * <p>When two consent rows for the same user are inserted within the same millisecond
 * (different microseconds), the newer row's cursor decodes to a
 * millisecond-floor {@code Instant} that is EARLIER than the older row's true
 * (sub-millisecond-precise) {@code grantedAt}. The keyset predicate in
 * {@code findByUserIdBeforeCursor} — {@code grantedAt < cursor OR (grantedAt = cursor AND
 * id < cursorId)} — then evaluates FALSE for the older row (it is neither strictly
 * before the truncated cursor, nor exactly equal to it), so the row is silently
 * dropped from the next page. This reproduces deterministically here by directly
 * forcing two rows into the same millisecond (bypassing the real-clock timing
 * dependency that made the bug intermittent in {@code ConsentMvcTest} — probability
 * of two consecutive real {@code Instant.now()} calls landing in the same millisecond
 * varies with machine load/JIT state, which is why the full-suite flake rate varied
 * between {@code mvn test} and {@code mvn clean test} runs).
 *
 * <p>Fix: {@code ConsentService} cursor codec must preserve full instant precision
 * (seconds + nanos), not truncate to milliseconds.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class ConsentServiceCursorPrecisionTest {

    @Autowired
    private ConsentRecordRepository consentRecords;

    @Autowired
    private UserRepository users;

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private EntityManager entityManager;

    private ConsentService consentService;

    @Test
    void cursorPagination_sameMillisecondDifferentNanos_secondPageStillReturnsOlderRow() {
        consentService = new ConsentService(consentRecords);

        User user = new User();
        user.setEmail("consent-cursor-precision@example.com");
        user = users.save(user);
        UUID userId = user.getId();

        // Row 1 (older, general_health) — grantedAt = T
        ConsentRecord r1 = new ConsentRecord();
        r1.setUserId(userId);
        r1.setConsentType("general_health");
        r1.setGranted(true);
        r1.setConsentTextVersion("v1.0");
        r1.setLocale("th");
        consentRecords.save(r1);
        testEntityManager.flush();

        // Row 2 (newer, cloud_storage) — grantedAt = T + 500 microseconds, deliberately
        // forced into the SAME millisecond as r1 via a native UPDATE (bypasses the
        // real-clock @PrePersist timing dependency so the collision is deterministic,
        // not luck-of-the-JIT/machine-load dependent).
        ConsentRecord r2 = new ConsentRecord();
        r2.setUserId(userId);
        r2.setConsentType("cloud_storage");
        r2.setGranted(true);
        r2.setConsentTextVersion("v1.0");
        r2.setLocale("th");
        consentRecords.save(r2);
        testEntityManager.flush();

        Instant r1GrantedAt = r1.getGrantedAt();
        Instant sameMillisLaterNanos = r1GrantedAt.plusNanos(500_000); // +0.5ms, same epoch milli
        entityManager.createNativeQuery(
                        "UPDATE consent_record SET granted_at = :ts WHERE id = :id")
                .setParameter("ts", java.sql.Timestamp.from(sameMillisLaterNanos))
                .setParameter("id", r2.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // Sanity precondition: both rows really do share the same truncated epoch milli,
        // so a naive millisecond-only cursor WOULD collide (proves this test actually
        // exercises the bug rather than trivially passing).
        assertThat(sameMillisLaterNanos.toEpochMilli())
                .as("test setup precondition: r2 must land in the same epoch millisecond as r1")
                .isEqualTo(r1GrantedAt.toEpochMilli());
        assertThat(sameMillisLaterNanos.getNano())
                .as("test setup precondition: r2 must be a real sub-millisecond instant AFTER r1, not equal")
                .isGreaterThan(r1GrantedAt.getNano());

        // First page (limit=1) — newest first, should return r2 (cloud_storage) + a nextCursor.
        ConsentListResponse firstPage = consentService.list(userId, null, 1);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.items().get(0).consentType()).isEqualTo("cloud_storage");
        assertThat(firstPage.nextCursor()).isNotNull();

        // Second page using the cursor from page 1 — MUST still return r1 (general_health).
        // Before the fix: the cursor's millisecond-truncated Instant sits strictly BETWEEN
        // r1's true sub-millisecond grantedAt and r2's, so neither "<" nor "=" matches r1 →
        // items.length() == 0 (the exact bug reproduced against a real full-suite run).
        ConsentListResponse secondPage = consentService.list(userId, firstPage.nextCursor(), 1);
        assertThat(secondPage.items())
                .as("cursor continuation must not silently drop a row due to sub-millisecond truncation")
                .hasSize(1);
        assertThat(secondPage.items().get(0).consentType()).isEqualTo("general_health");
        assertThat(secondPage.nextCursor()).isNull();
    }
}
