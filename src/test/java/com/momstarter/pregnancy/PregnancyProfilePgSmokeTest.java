package com.momstarter.pregnancy;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>PDPA compliance smoke test — pregnancy_profile name-cipher columns on real
 * PostgreSQL (launch-gate).</strong>
 *
 * <p>Runs against a real {@code postgres:16} container ({@code momstarter-pg},
 * started via {@code docker run}) when {@code localhost:5432} is reachable; otherwise
 * the entire class is SKIPPED (JUnit 5 {@code @EnabledIf}).
 *
 * <h2>What this test proves (PDPA ม.33 compliance — blocking build-acceptance criterion)</h2>
 * <p>PDPA assessment name-fields-design.md §5 requires proof on REAL Postgres (not H2) that:
 * <ol>
 *   <li><strong>(a) Column existence + bytea + NULL acceptance on real Postgres.</strong>
 *       H2 in PostgreSQL MODE accepts many column types without strict enforcement; this test
 *       confirms the three cipher columns ({@code mother_first_name_cipher},
 *       {@code mother_last_name_cipher}, {@code baby_name_cipher}) physically exist on real
 *       Postgres, accept binary data, and can be explicitly set to {@code NULL}
 *       (i.e. the migration applied cleanly).</li>
 *   <li><strong>(b) Per-row cipher-NULL shred NULLs all three columns on real Postgres.</strong>
 *       Under the MVP no-op cipher, the only PDPA T0 evidence that identity-PII name bytes
 *       are destroyed is an explicit NULL write.  This test calls the REAL production method
 *       {@link PregnancyProfileRepository#shredCiphersByUserId} — the exact repository method
 *       the tombstone path will use — and verifies the NULL persists to REAL Postgres
 *       (H2 can mask bytea binding issues per the {@code h2-masks-jsonb-binding} memory).</li>
 * </ol>
 *
 * <h2>Pre-condition</h2>
 * <pre>
 * docker run -d --name momstarter-pg -p 5432:5432 \
 *   -e POSTGRES_DB=momstarter -e POSTGRES_USER=momstarter \
 *   -e POSTGRES_PASSWORD=momstarter \
 *   postgres:16
 * </pre>
 *
 * <h2>Launch-gate status</h2>
 * <p>If this sandbox cannot reach Postgres, all tests in this class show as SKIPPED — expected.
 * <strong>A human MUST execute these tests against real Postgres before production launch</strong>
 * to satisfy PDPA name-fields-design.md §5c blocking build-acceptance criterion.
 * SKIPPED does NOT mean PASSED.
 *
 * <h2>Transaction isolation</h2>
 * <p>{@code @Transactional} rolls back each test method. {@code @BeforeEach} creates a
 * unique user per test (no {@code deleteAll()} to avoid FK-ordering issues on real Postgres).
 *
 * <h2>Why JdbcTemplate for setup and verification</h2>
 * <p>The {@link PregnancyProfile} JPA entity does not yet map the three cipher columns
 * ({@code motherFirstNameCipher} etc. — that is {@code springboot-backend-dev}'s next step).
 * JdbcTemplate is used for:
 * <ul>
 *   <li>Writing test cipher bytes into the three columns ({@code UPDATE ... SET ... WHERE user_id = ?}).</li>
 *   <li>Reading the columns back ({@code SELECT ... FROM pregnancy_profile WHERE user_id = ?})
 *       after the shred, because {@link JpaRepository#findById} would ignore unmapped columns.</li>
 * </ul>
 * Once the entity fields are mapped, an entity-level reload could replace the JDBC verification,
 * but the JDBC approach is strictly correct here and robust to mapping changes.
 */
@EnabledIf("pgReachable")
@SpringBootTest
@TestPropertySource(properties = {
        // Override application-test.yml's H2 URL with the running PG container.
        "spring.datasource.url=jdbc:postgresql://localhost:5432/momstarter",
        "spring.datasource.username=momstarter",
        "spring.datasource.password=momstarter",
        "spring.jpa.hibernate.ddl-auto=none",
        // Flyway safe: already-applied migrations are skipped via flyway_schema_history.
        "spring.flyway.enabled=true",
        // Disable DevModeGuard
        "momstarter.dev.auto-verify-email=false",
        // High limits so tests don't hit the rate limiter
        "momstarter.ratelimit.login-per-ip-per-min=1000000",
        "momstarter.ratelimit.register-per-ip-per-min=1000000",
        "momstarter.ratelimit.resend-per-ip-per-min=1000000",
        "momstarter.ratelimit.forgot-per-ip-per-min=1000000",
        "momstarter.ratelimit.reset-per-ip-per-min=1000000",
        "momstarter.ratelimit.verify-email-per-ip-per-min=1000000"
})
@Transactional
class PregnancyProfilePgSmokeTest {

    /**
     * Probed by {@code @EnabledIf("pgReachable")} before Spring loads the ApplicationContext.
     * Returns {@code true} when PostgreSQL is reachable on {@code localhost:5432};
     * {@code false} causes JUnit to mark the entire class as SKIPPED (not FAILED).
     */
    static boolean pgReachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 5432), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRepository users;

    @Autowired
    private PregnancyProfileRepository profiles;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID userId;

    /**
     * Creates a fresh user and a minimal pregnancy profile per test.
     * {@code @Transactional} rollback handles cleanup — no explicit teardown needed.
     */
    @BeforeEach
    void setup() {
        User user = new User();
        user.setEmail("pg-pp-smoke-" + UUID.randomUUID() + "@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();

        // Insert a minimal pregnancy profile (existing entity fields only).
        PregnancyProfile profile = new PregnancyProfile();
        profile.setUserId(userId);
        profile.setEdd(LocalDate.of(2027, 3, 15));
        profile.setEddBasis("due_date");
        profiles.saveAndFlush(profile);
    }

    // -------------------------------------------------------------------------
    // (a) Column existence: all three cipher columns exist + accept bytea + NULL
    // -------------------------------------------------------------------------

    /**
     * <strong>Assertion (a) — three name cipher columns exist on real Postgres,
     * accept bytea writes, and can be set to NULL.</strong>
     *
     * <p>Uses JdbcTemplate to UPDATE the three cipher columns with non-NULL bytes,
     * then SELECT to confirm they are stored (non-NULL round-trip).  Then sets them
     * explicitly to NULL and re-SELECTs to confirm the NULL is accepted.
     *
     * <p>If the Flyway migration V20260707000018 was NOT applied to this Postgres instance,
     * the UPDATE will throw {@code PSQLException: column "mother_first_name_cipher" of
     * relation "pregnancy_profile" does not exist} — causing this test to fail with a clear
     * migration-not-applied signal (not a silent pass).
     */
    @Test
    void nameCipherColumns_existAcceptByteaAndNull_onRealPostgres() {
        // Write non-NULL bytes to the three cipher columns via JDBC
        // (JPA entity does not map these columns yet — springboot-backend-dev's next step)
        int updated = jdbc.update(
                "UPDATE pregnancy_profile "
                + "SET mother_first_name_cipher = ?, "
                +     "mother_last_name_cipher = ?, "
                +     "baby_name_cipher = ? "
                + "WHERE user_id = ?",
                new byte[]{0x01, 0x02, 0x03},  // mother first name — plaintext bytes (MVP posture)
                new byte[]{0x04, 0x05, 0x06},  // mother last name  — plaintext bytes
                new byte[]{0x07, 0x08, 0x09},  // baby name         — plaintext bytes
                userId);

        assertThat(updated)
                .as("(a) UPDATE must affect exactly one pregnancy_profile row")
                .isEqualTo(1);

        // Flush + clear L1 so the subsequent SELECT goes to real Postgres
        em.flush();
        em.clear();

        // Verify all three columns hold non-NULL bytes
        byte[] firstName = jdbc.queryForObject(
                "SELECT mother_first_name_cipher FROM pregnancy_profile WHERE user_id = ?",
                byte[].class, userId);
        byte[] lastName = jdbc.queryForObject(
                "SELECT mother_last_name_cipher FROM pregnancy_profile WHERE user_id = ?",
                byte[].class, userId);
        byte[] babyName = jdbc.queryForObject(
                "SELECT baby_name_cipher FROM pregnancy_profile WHERE user_id = ?",
                byte[].class, userId);

        assertThat(firstName)
                .as("(a) mother_first_name_cipher must accept and return bytea on real Postgres")
                .isNotNull()
                .isEqualTo(new byte[]{0x01, 0x02, 0x03});
        assertThat(lastName)
                .as("(a) mother_last_name_cipher must accept and return bytea on real Postgres")
                .isNotNull()
                .isEqualTo(new byte[]{0x04, 0x05, 0x06});
        assertThat(babyName)
                .as("(a) baby_name_cipher must accept and return bytea on real Postgres")
                .isNotNull()
                .isEqualTo(new byte[]{0x07, 0x08, 0x09});

        // Now explicitly NULL the columns to verify nullable DDL is enforced
        int nulled = jdbc.update(
                "UPDATE pregnancy_profile "
                + "SET mother_first_name_cipher = NULL, "
                +     "mother_last_name_cipher = NULL, "
                +     "baby_name_cipher = NULL "
                + "WHERE user_id = ?",
                userId);

        assertThat(nulled)
                .as("(a) explicit NULL UPDATE must affect exactly one row")
                .isEqualTo(1);

        em.flush();
        em.clear();

        // Confirm all three are NULL after explicit null write
        Integer notNullCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pregnancy_profile "
                + "WHERE user_id = ? "
                + "AND (mother_first_name_cipher IS NOT NULL "
                +      "OR mother_last_name_cipher IS NOT NULL "
                +      "OR baby_name_cipher IS NOT NULL)",
                Integer.class, userId);

        assertThat(notNullCount)
                .as("(a) all three cipher columns must be NULL after explicit null write on real Postgres")
                .isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // (b) Crypto-shred: shredCiphersByUserId NULLs all three columns on real Postgres
    // -------------------------------------------------------------------------

    /**
     * <strong>Assertion (b) — {@link PregnancyProfileRepository#shredCiphersByUserId}
     * NULLs all three name cipher columns on real Postgres (🔴 PDPA ม.33 must-prove).</strong>
     *
     * <p>Under the MVP no-op cipher, there is no DEK to shred — the only T0 evidence that
     * identity-PII name bytes are explicitly destroyed on the profile tombstone path is this
     * explicit NULL write.  This test calls the REAL production repository method
     * {@link PregnancyProfileRepository#shredCiphersByUserId} — the exact method the tombstone
     * path will invoke at runtime — so that any future regression (e.g. removing the NULL
     * assignments) causes this test to FAIL rather than silently stay green.
     *
     * <p>PDPA compliance: name-fields-design.md §5c — per-row cipher-NULL shred on tombstone
     * is the mandatory T0 belt-and-suspenders evidence for the names beyond the DEK-based
     * global shred.
     *
     * <p>H2 masking: H2 in PostgreSQL MODE accepts bytea writes but may mask NULL persistence
     * edge cases ({@code h2-masks-jsonb-binding} memory pattern).  Real Postgres is required
     * to validate that the NULL UPDATE genuinely reaches durable storage.
     */
    @Test
    void shredCiphersByUserId_nullsAllThreeNameCiphers_onRealPostgres() {
        // Seed the three cipher columns with non-NULL bytes via JDBC
        // (JPA entity does not yet map these columns — springboot-backend-dev's next step)
        jdbc.update(
                "UPDATE pregnancy_profile "
                + "SET mother_first_name_cipher = ?, "
                +     "mother_last_name_cipher = ?, "
                +     "baby_name_cipher = ? "
                + "WHERE user_id = ?",
                new byte[]{0x41, 0x6E, 0x6E, 0x61},      // "Anna"   (plaintext bytes, MVP posture)
                new byte[]{0x53, 0x6D, 0x69, 0x74, 0x68}, // "Smith"  (plaintext bytes)
                new byte[]{0x4C, 0x69, 0x6C, 0x79},        // "Lily"   (plaintext bytes)
                userId);

        // Flush Hibernate session so the JPA entity state matches the JDBC write
        em.flush();
        em.clear();

        // Invoke the REAL production shred method — the exact repository method
        // the tombstone path will call at runtime (name-fields-design.md §5c).
        // If shredCiphersByUserId is later broken (e.g. columns removed from the UPDATE),
        // this test will fail — not stay green.
        int shredded = profiles.shredCiphersByUserId(userId);

        assertThat(shredded)
                .as("(b) shredCiphersByUserId must UPDATE exactly one row")
                .isEqualTo(1);

        // Flush + evict L1 so the JDBC SELECT below reads from real Postgres,
        // not from the stale in-memory entity (which Hibernate would keep as-is since
        // the @Modifying clearAutomatically=true only clears the EntityManager cache).
        // Mirror of MedicationPgSmokeTest:241-245 / SelfLogPgSmokeTest:225-230.
        em.flush();
        em.clear();

        // Verify all three columns are NULL on real Postgres via JDBC
        // (not via profiles.findById — JPA would ignore unmapped columns)
        byte[] firstName = jdbc.queryForObject(
                "SELECT mother_first_name_cipher FROM pregnancy_profile WHERE user_id = ?",
                byte[].class, userId);
        byte[] lastName = jdbc.queryForObject(
                "SELECT mother_last_name_cipher FROM pregnancy_profile WHERE user_id = ?",
                byte[].class, userId);
        byte[] babyName = jdbc.queryForObject(
                "SELECT baby_name_cipher FROM pregnancy_profile WHERE user_id = ?",
                byte[].class, userId);

        assertThat(firstName)
                .as("(b) mother_first_name_cipher must be NULL on real Postgres after shred")
                .isNull();
        assertThat(lastName)
                .as("(b) mother_last_name_cipher must be NULL on real Postgres after shred")
                .isNull();
        assertThat(babyName)
                .as("(b) baby_name_cipher must be NULL on real Postgres after shred")
                .isNull();
    }
}
