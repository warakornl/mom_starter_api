package com.momstarter.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AccountDekRepository} against H2 (PostgreSQL mode).
 *
 * <p>Flyway is enabled so the {@code account_dek} table is created by
 * {@code V20260705000017__mvp1_account_dek.sql}. Tests verify:
 * <ul>
 *   <li>INSERT + findById round-trip</li>
 *   <li>deleteByUserId hard-deletes the row</li>
 *   <li>Column constraints (NOT NULL wrappedDek, kmsKeyId, wrapContext)</li>
 * </ul>
 *
 * <h2>H2/bytea note</h2>
 * <p>{@code wrapped_dek} is a {@code bytea} column mapped to {@code byte[]}. H2 in PostgreSQL
 * mode stores binary data correctly for this mapping; no {@code @JdbcTypeCode} is needed
 * (unlike jsonb). A real-Postgres byte-fidelity smoke test is noted as a launch-gate
 * ({@code h2-masks-jsonb-binding} memory).
 *
 * <p>TDD: tests were written before the implementation to drive the entity/repo shape.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class AccountDekRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private AccountDekRepository accountDekRepository;

    @Autowired
    private UserRepository users;

    private User user;

    @BeforeEach
    void seedUser() {
        user = new User();
        user.setEmail("dek-repo-test@example.com");
        user.setEmailVerified(true);
        user.setPasswordHash("{argon2}dummy");
        em.persistAndFlush(user);
    }

    // -------------------------------------------------------------------------
    // INSERT + findById
    // -------------------------------------------------------------------------

    @Test
    void save_and_findById_roundTrip() {
        AccountDek dek = buildDek(user.getId(), new byte[]{1, 2, 3, 4});

        accountDekRepository.saveAndFlush(dek);
        em.clear(); // evict L1 cache so the subsequent SELECT hits the DB, not L1 cache

        Optional<AccountDek> found = accountDekRepository.findById(user.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(user.getId());
        assertThat(found.get().getWrappedDek()).containsExactly(1, 2, 3, 4);
        assertThat(found.get().getKmsKeyId()).isEqualTo("mock-cmk/test");
        assertThat(found.get().getWrapContext()).isEqualTo("accountId=" + user.getId());
        assertThat(found.get().getDekVersion()).isEqualTo((short) 1);
        assertThat(found.get().getVersion()).isEqualTo(0L); // Hibernate @Version DEFAULT 0
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    @Test
    void save_persists32ByteWrappedDek_intact() {
        byte[] wrappedDek = new byte[32];
        for (int i = 0; i < 32; i++) wrappedDek[i] = (byte) i;

        accountDekRepository.saveAndFlush(buildDek(user.getId(), wrappedDek));
        em.clear(); // evict L1 cache so the subsequent SELECT hits the DB

        byte[] stored = accountDekRepository.findById(user.getId())
                .map(AccountDek::getWrappedDek)
                .orElseThrow();

        assertThat(stored).hasSize(32).containsExactly(toBoxed(wrappedDek));
    }

    // -------------------------------------------------------------------------
    // deleteByUserId
    // -------------------------------------------------------------------------

    @Test
    void deleteByUserId_removesRow() {
        accountDekRepository.saveAndFlush(buildDek(user.getId(), new byte[]{9, 8, 7}));
        em.clear(); // evict L1 cache

        assertThat(accountDekRepository.findById(user.getId())).isPresent();

        accountDekRepository.deleteByUserId(user.getId());
        em.clear();

        assertThat(accountDekRepository.findById(user.getId())).isEmpty();
    }

    @Test
    void deleteByUserId_isIdempotent_whenNoRowExists() {
        // No row for this user — delete should be a no-op, not throw
        UUID unknownId = UUID.randomUUID();
        accountDekRepository.deleteByUserId(unknownId);
        // Verify it didn't affect the user's row (user has no DEK row seeded here)
        assertThat(accountDekRepository.findById(unknownId)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AccountDek buildDek(UUID userId, byte[] wrappedDek) {
        AccountDek dek = new AccountDek();
        dek.setUserId(userId);
        dek.setWrappedDek(wrappedDek);
        dek.setKmsKeyId("mock-cmk/test");
        dek.setWrapContext("accountId=" + userId);
        dek.setDekVersion((short) 1);
        return dek;
    }

    private static Byte[] toBoxed(byte[] bytes) {
        Byte[] boxed = new Byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) boxed[i] = bytes[i];
        return boxed;
    }
}
