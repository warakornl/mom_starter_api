package com.momstarter.dev;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fail-closed guard for momstarter.dev.expose-reset-token=true.
 * Guard is ANDed: G2 (allow-list profile) AND G3 (allow-list datasource) AND G4 (fail on ambiguity).
 *
 * <p>Test-design note: tests verifying G3 (T-4/T-5/T-6) MUST have active profile = "local"
 * so G2 passes and G3 is the sole rejecting clause. Tests verifying G2 (T-1/T-2/T-3) use a
 * known-good local datasource so G3 passes and G2 is the sole rejecting clause.
 */
class ResetTokenExposureGuardTest {

    // ── G2 tests (profile allow-list) ─────────────────────────────────────────────

    /** T-1: prod profile → G2 rejects (deny-list second layer). */
    @Test
    void T1_prodProfile_datasourceLocalOk_failsToStart() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"prod"}, "jdbc:h2:mem:momstarter");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expose-reset-token");
    }

    /** T-2: staging profile + local datasource → G2 allow-list rejects (staging not in allow-list). */
    @Test
    void T2_stagingProfile_datasourceLocalOk_failsToStart_G2() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"staging"}, "jdbc:h2:mem:momstarter");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expose-reset-token");
    }

    /** T-3: no active profile (default) + local datasource → G2 throws because 'local' is absent. */
    @Test
    void T3_noLocalProfile_datasourceLocalOk_failsToStart_G2() {
        ResetTokenExposureGuard guard = guardWith(new String[]{}, "jdbc:h2:mem:momstarter");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expose-reset-token");
    }

    /** T-3b: 'test' profile but no 'local' → G2 throws. */
    @Test
    void T3b_testProfileNoLocal_failsToStart_G2() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"test"}, "jdbc:h2:mem:momstarter");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class);
    }

    /** T-1b: 'production' (longform) → G2 deny-list rejects even with local profile. */
    @Test
    void T1b_productionProfile_withLocal_failsToStart_G2() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local", "production"}, "jdbc:h2:mem:momstarter");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class);
    }

    // ── G3 tests (datasource allow-list — all run with 'local' profile to isolate G3) ──

    /** T-4: local profile + remote postgres → G3 throws. */
    @Test
    void T4_localProfile_remotePostgres_failsToStart_G3() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local"},
                "jdbc:postgresql://prod-host/db");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expose-reset-token");
    }

    /** T-5: local profile + remote H2 (jdbc:h2:tcp:) → G3 throws (non-embedded H2). */
    @Test
    void T5_localProfile_remoteH2Tcp_failsToStart_G3() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local"},
                "jdbc:h2:tcp://remote:9092/db");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expose-reset-token");
    }

    /** T-6: local profile + substring-localhost trap → G3 parses host and rejects. */
    @Test
    void T6_localProfile_substringLocalhostTrap_failsToStart_G3() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local"},
                "jdbc:postgresql://localhost.attacker.com/db");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expose-reset-token");
    }

    /** remote H2 ssl variant → G3 throws. */
    @Test
    void T5b_localProfile_remoteH2Ssl_failsToStart_G3() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local"},
                "jdbc:h2:ssl://remote:9093/db");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class);
    }

    /** RDS host with local profile → G3 throws. */
    @Test
    void localProfile_rdsUrl_failsToStart_G3() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local"},
                "jdbc:postgresql://momstarter.rds.amazonaws.com:5432/momstarter");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class);
    }

    // ── G4 tests (fail-closed on absent/unparseable URL) ───────────────────────────

    /** T-15/G4: absent datasource URL with local profile → G4 throws. */
    @Test
    void G4_localProfile_absentDatasourceUrl_failsToStart() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local"}, "");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expose-reset-token");
    }

    /** G4: null datasource URL with local profile → G4 throws. */
    @Test
    void G4_localProfile_nullDatasourceUrl_failsToStart() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local"}, null);

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Happy path (T-7) ──────────────────────────────────────────────────────────

    /** T-7: local profile + embedded H2 mem → guard passes. */
    @Test
    void T7_localProfile_embeddedH2Mem_allowsStartup() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local"},
                "jdbc:h2:mem:momstarter;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");

        assertThatCode(guard::checkSafety).doesNotThrowAnyException();
    }

    /** T-7b: local profile + embedded H2 file → guard passes. */
    @Test
    void T7b_localProfile_embeddedH2File_allowsStartup() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local"},
                "jdbc:h2:file:./testdb");

        assertThatCode(guard::checkSafety).doesNotThrowAnyException();
    }

    /** T-7c: local profile + localhost postgres → guard passes. */
    @Test
    void T7c_localProfile_localhostPostgres_allowsStartup() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local"},
                "jdbc:postgresql://localhost:5432/momstarter");

        assertThatCode(guard::checkSafety).doesNotThrowAnyException();
    }

    /** T-7d: local profile + 127.0.0.1 → guard passes. */
    @Test
    void T7d_localProfile_loopbackIpv4_allowsStartup() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"local"},
                "jdbc:postgresql://127.0.0.1:5432/momstarter");

        assertThatCode(guard::checkSafety).doesNotThrowAnyException();
    }

    /** local + local profile case-insensitive (CASE) → passes. */
    @Test
    void localProfileUpperCase_allowsStartup() {
        ResetTokenExposureGuard guard = guardWith(new String[]{"LOCAL"},
                "jdbc:h2:mem:test");

        assertThatCode(guard::checkSafety).doesNotThrowAnyException();
    }

    // ── helper ────────────────────────────────────────────────────────────────────

    private ResetTokenExposureGuard guardWith(String[] activeProfiles, String datasourceUrl) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(activeProfiles);
        ResetTokenExposureGuard guard = new ResetTokenExposureGuard(env);
        ReflectionTestUtils.setField(guard, "datasourceUrl", datasourceUrl);
        return guard;
    }
}
