package com.momstarter.account;

import com.momstarter.selflog.SelfLog;
import com.momstarter.selflog.SelfLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>PgSmoke: crypto-shred shred-proof on real PostgreSQL (launch-gate).</strong>
 *
 * <p>Runs against a real {@code postgres:16} container ({@code momstarter-pg}) when
 * {@code localhost:5432} is reachable. The entire class is SKIPPED if Postgres is unavailable
 * (JUnit 5 {@code @EnabledIf}). <strong>SKIPPED DOES NOT MEAN PASSED</strong> — a human MUST
 * execute this against real Postgres before production launch.
 *
 * <h2>Pre-condition</h2>
 * <pre>
 * docker run -d --name momstarter-pg -p 5432:5432 \
 *   -e POSTGRES_DB=momstarter -e POSTGRES_USER=momstarter -e POSTGRES_PASSWORD=momstarter \
 *   postgres:16
 * </pre>
 *
 * <h2>What this test proves (PDPA compliance, ADR RULING 6 / appsec-engineer Task 0b)</h2>
 * <p>Three-phase proof that crypto-shred works on real Postgres storage:
 * <ol>
 *   <li><strong>Phase 1 — positive baseline</strong>: an {@code account_dek} row can be inserted
 *       and read back on real Postgres (byte-exact round-trip for {@code wrapped_dek} bytea).</li>
 *   <li><strong>Phase 2 — shred action</strong>: {@code deleteByUserId} hard-deletes the row;
 *       {@code SELECT count(*) FROM account_dek WHERE user_id = ?} returns 0.
 *       The {@code users} row is unaffected (FK-leaf proof: deleting account_dek does NOT
 *       cascade to users; the FK direction is account_dek→users, not the reverse).</li>
 *   <li><strong>Phase 3 — negative proof (launch-gate assertion)</strong>: after the shred,
 *       {@code accountDekRepository.findById(userId)} returns empty — the wrapped DEK is
 *       gone and {@code KMS.Decrypt(wrappedDek)} cannot be called without it.
 *       TODO(cipher-slice): once {@code FieldEnvelopeDecryptor} + {@code MockKmsClient} are
 *       wired (Phase-1 sub-slice d/e), add the "decrypt attempt must throw" assertion here
 *       per migration-design §PgSmoke Phase 3d and appsec RULING 6 criterion 3.</li>
 * </ol>
 *
 * <h2>Why real Postgres (not H2)</h2>
 * <p>H2 in PostgreSQL mode accepts {@code bytea} writes via JDBC but may mask byte-fidelity
 * issues in edge cases ({@code h2-masks-jsonb-binding} memory). Real Postgres is required for
 * a meaningful shred proof (appsec RULING 6 / migration-design §PgSmoke extension design).
 *
 * <h2>Transaction isolation</h2>
 * <p>{@code @Transactional} rolls back each test method — no persistent test data is written
 * to the real Postgres database.
 */
@EnabledIf("pgReachable")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/momstarter",
        "spring.datasource.username=momstarter",
        "spring.datasource.password=momstarter",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "momstarter.dev.auto-verify-email=false",
        "momstarter.ratelimit.login-per-ip-per-min=1000000",
        "momstarter.ratelimit.register-per-ip-per-min=1000000",
        "momstarter.ratelimit.resend-per-ip-per-min=1000000",
        "momstarter.ratelimit.forgot-per-ip-per-min=1000000",
        "momstarter.ratelimit.reset-per-ip-per-min=1000000",
        "momstarter.ratelimit.verify-email-per-ip-per-min=1000000"
})
@Transactional
class AccountDekPgSmokeTest {

    /**
     * Probed by {@code @EnabledIf("pgReachable")} before Spring loads the ApplicationContext.
     * Returns {@code true} when PostgreSQL is reachable on {@code localhost:5432};
     * {@code false} causes JUnit to mark the entire class as SKIPPED — expected in CI.
     */
    static boolean pgReachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 5432), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PersistenceContext private EntityManager em;

    @Autowired private UserRepository users;
    @Autowired private AccountDekRepository accountDekRepository;
    @Autowired private SelfLogRepository selfLogs;

    private UUID userId;

    /**
     * Creates a fresh user per test. {@code @Transactional} rollback handles cleanup.
     */
    @BeforeEach
    void setup() {
        User user = new User();
        user.setEmail("pg-dek-smoke-" + UUID.randomUUID() + "@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
    }

    // -------------------------------------------------------------------------
    // Phase 1 — positive baseline: account_dek round-trip on real Postgres
    // -------------------------------------------------------------------------

    /**
     * <strong>Phase 1 — positive baseline on real Postgres.</strong>
     *
     * <p>Inserts an {@code account_dek} row and reads it back via {@code findById}.
     * Verifies that {@code wrapped_dek} (bytea) survives the insert-read round-trip
     * byte-exact on real Postgres — proving the storage primitive works before the shred.
     *
     * <p>This is the "encrypt→decrypt-ok" baseline required by appsec RULING 6 criterion 1.
     * The actual AES-GCM encrypt→decrypt step is deferred to the cipher sub-slice;
     * this slice verifies the DEK storage layer (bytea round-trip) on real Postgres.
     */
    @Test
    void phase1_accountDekInsertAndFindById_roundTrip_onRealPostgres() {
        byte[] mockWrappedDek = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                                  0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
                                  0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
                                  0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20};

        AccountDek dek = new AccountDek();
        dek.setUserId(userId);
        dek.setWrappedDek(mockWrappedDek);
        dek.setKmsKeyId("arn:aws:kms:ap-southeast-7:000000000000:key/mock-cmk-pgsmoke");
        dek.setWrapContext("accountId=" + userId);
        accountDekRepository.saveAndFlush(dek);

        em.flush();
        em.clear();

        AccountDek reloaded = accountDekRepository.findById(userId).orElseThrow();

        assertThat(reloaded.getWrappedDek())
                .as("Phase 1: wrapped_dek must survive bytea round-trip on real Postgres (byte-exact)")
                .containsExactly(toBoxed(mockWrappedDek));
        assertThat(reloaded.getKmsKeyId()).isNotBlank();
        assertThat(reloaded.getWrapContext()).isEqualTo("accountId=" + userId);
        assertThat(reloaded.getDekVersion()).isEqualTo((short) 1);
    }

    // -------------------------------------------------------------------------
    // Phase 2 — shred action: DELETE account_dek on real Postgres
    // -------------------------------------------------------------------------

    /**
     * <strong>Phase 2 — shred action on real Postgres (appsec RULING 6 criterion 2).</strong>
     *
     * <p>Seeds an {@code account_dek} row (T0 path simulation), calls
     * {@link AccountDekRepository#deleteByUserId} (the exact production method used in
     * {@code AccountService.deleteAccount}), and asserts:
     * <ol>
     *   <li>The account_dek row is physically gone on real Postgres (count = 0).</li>
     *   <li>The {@code users} row is unaffected — the FK is account_dek→users (RESTRICT),
     *       not the reverse; deleting the leaf does NOT cascade to the parent.</li>
     * </ol>
     *
     * <p>This proves the hard-DELETE is FK-safe and works on real Postgres storage.
     */
    @Test
    void phase2_shredAction_deleteByUserId_isGone_fkSafe_onRealPostgres() {
        AccountDek dek = new AccountDek();
        dek.setUserId(userId);
        dek.setWrappedDek(new byte[]{1, 2, 3, 4});
        dek.setKmsKeyId("mock-cmk/pgsmoke");
        dek.setWrapContext("accountId=" + userId);
        accountDekRepository.saveAndFlush(dek);

        // Also seed a health-row placeholder (simulating existing ciphertext that
        // should remain intact after the shred — shred = key destruction, not byte wipe).
        SelfLog healthRow = new SelfLog();
        healthRow.setId(UUID.randomUUID());
        healthRow.setUserId(userId);
        healthRow.setMetricType("weight");
        healthRow.setLoggedAt(LocalDateTime.of(2026, 7, 6, 10, 0));
        // MVP: value_numeric holds unencrypted bytes under the no-op cipher.
        // Once the AES-GCM cipher slice lands, this will be a real ciphertext envelope.
        healthRow.setValueNumeric(new byte[]{7, 8, 9});
        selfLogs.saveAndFlush(healthRow);

        em.flush();
        em.clear();

        // --- Phase 2: T0 shred ---
        accountDekRepository.deleteByUserId(userId);

        em.flush();
        em.clear();

        // Assertion 2a: account_dek row is physically gone on real Postgres
        assertThat(accountDekRepository.findById(userId))
                .as("Phase 2: account_dek row must be hard-deleted (T0 crypto-shred on real Postgres)")
                .isEmpty();

        // Assertion 2b: users row is unaffected — FK leaf deletion does NOT cascade to parent
        assertThat(users.findById(userId))
                .as("Phase 2: users row must survive the account_dek deletion (FK leaf, no cascade)")
                .isPresent();

        // Assertion 2c: the health-row ciphertext bytes are physically unchanged on real Postgres.
        // Crypto-shred = irrecoverability via key destruction, NOT byte deletion.
        // The ciphertext bytes remain in the DB; they become irrecoverable once the DEK is gone.
        SelfLog healthCheck = selfLogs.findById(healthRow.getId()).orElseThrow();
        assertThat(healthCheck.getValueNumeric())
                .as("Phase 2: health-row ciphertext bytes must be physically unchanged "
                        + "(crypto-shred does not wipe bytes — it destroys the key)")
                .containsExactly(7, 8, 9);
    }

    // -------------------------------------------------------------------------
    // Phase 3 — negative proof: DEK gone → findById empty
    // -------------------------------------------------------------------------

    /**
     * <strong>Phase 3 — negative proof on real Postgres (appsec RULING 6 criterion 3).</strong>
     *
     * <p>After the T0 shred, {@code accountDekRepository.findById(userId)} must return empty
     * — there is no wrapped DEK to pass to {@code KMS.Decrypt}. Any code path that requires
     * the DEK will fail because the lookup returns empty, not because KMS rejects the call.
     *
     * <p><strong>TODO(cipher-slice): Add decrypt-fails assertion after AES-GCM FieldEnvelopeDecryptor
     * is wired (Phase-1 sub-slice d/e).</strong>
     * Per migration-design §PgSmoke Phase 3d and appsec RULING 6:
     * <pre>
     * // PLACEHOLDER — add once FieldEnvelopeDecryptor + MockKmsClient are integrated:
     * // byte[] storedCiphertext = healthCheck.getValueNumeric();
     * // assertThatThrownBy(() ->
     * //     fieldEnvelopeDecryptor.decryptFromBase64(
     * //         Base64.getEncoder().encodeToString(storedCiphertext), dek, aad))
     * //     .as("Phase 3: decrypt must FAIL without the DEK — no fallback, no identity read")
     * //     .isInstanceOf(IllegalStateException.class);  // or the appropriate cipher exception
     * </pre>
     */
    @Test
    void phase3_negativePoof_shredredDek_findByIdReturnsEmpty_onRealPostgres() {
        AccountDek dek = new AccountDek();
        dek.setUserId(userId);
        dek.setWrappedDek(new byte[]{0x0A, 0x0B, 0x0C, 0x0D});
        dek.setKmsKeyId("mock-cmk/pgsmoke");
        dek.setWrapContext("accountId=" + userId);
        accountDekRepository.saveAndFlush(dek);

        em.flush();
        em.clear();

        // Shred the DEK (T0 path)
        accountDekRepository.deleteByUserId(userId);

        em.flush();
        em.clear();

        // Phase 3a: findById must return empty — the wrapped DEK is gone
        assertThat(accountDekRepository.findById(userId))
                .as("Phase 3: findById must return empty after shred — wrapped DEK is gone on real Postgres")
                .isEmpty();

        // Phase 3b: irrecoverability proof (server-side)
        // Without the wrapped DEK row, KMS.Decrypt cannot be invoked — the lookup itself fails.
        // This is the "server-side irrecoverability guarantee" (ADR Decision 1.2):
        // once the account_dek row is deleted, the plaintext DEK cannot be reconstructed
        // by the server (no wrapped blob to pass to KMS → no DEK → no field decryption).
        //
        // TODO(cipher-slice): add the FieldEnvelopeDecryptor "decrypt must throw" assertion
        // per migration-design Phase 3d once AES-GCM is wired (Phase-1 sub-slice d/e).
        // The placeholder is documented in the method Javadoc above.
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static Byte[] toBoxed(byte[] bytes) {
        Byte[] boxed = new Byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) boxed[i] = bytes[i];
        return boxed;
    }
}
