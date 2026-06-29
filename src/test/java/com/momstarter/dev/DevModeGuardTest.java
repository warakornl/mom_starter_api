package com.momstarter.dev;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fail-safe guard tests: when momstarter.dev.auto-verify-email=true is active,
 * the guard must reject startup if the environment looks like production or uses
 * a non-local database URL.  In a genuinely local setup it must pass silently.
 */
class DevModeGuardTest {

    // ── fail cases ────────────────────────────────────────────────────────────────

    @Test
    void prodProfile_refusesStartupWithIllegalState() {
        DevModeGuard guard = guardWith(new String[]{"prod"}, "jdbc:postgresql://localhost:5432/momstarter");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEV MODE");
    }

    @Test
    void prodProfileMixedCase_refusesStartup() {
        DevModeGuard guard = guardWith(new String[]{"PROD"}, "jdbc:postgresql://localhost:5432/momstarter");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void localProfileButRemoteRdsDatabase_refusesStartup() {
        DevModeGuard guard = guardWith(new String[]{"local"},
                "jdbc:postgresql://momstarter.rds.amazonaws.com:5432/momstarter");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEV MODE");
    }

    @Test
    void noProfileButRemoteDatabase_refusesStartup() {
        DevModeGuard guard = guardWith(new String[]{}, "jdbc:postgresql://db.internal.company.com:5432/momstarter");

        assertThatThrownBy(guard::checkSafety)
                .isInstanceOf(IllegalStateException.class);
    }

    // ── safe cases ────────────────────────────────────────────────────────────────

    @Test
    void localProfile_localhostDb_allowsStartup() {
        DevModeGuard guard = guardWith(new String[]{"local"}, "jdbc:postgresql://localhost:5432/momstarter");

        assertThatCode(guard::checkSafety).doesNotThrowAnyException();
    }

    @Test
    void testProfile_localhostDb_allowsStartup() {
        DevModeGuard guard = guardWith(new String[]{"test"},
                "jdbc:h2:mem:momstarter;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");

        assertThatCode(guard::checkSafety).doesNotThrowAnyException();
    }

    @Test
    void localProfile_loopbackIpv4Db_allowsStartup() {
        DevModeGuard guard = guardWith(new String[]{"local"}, "jdbc:postgresql://127.0.0.1:5432/momstarter");

        assertThatCode(guard::checkSafety).doesNotThrowAnyException();
    }

    // ── helper ────────────────────────────────────────────────────────────────────

    private DevModeGuard guardWith(String[] activeProfiles, String datasourceUrl) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(activeProfiles);
        DevModeGuard guard = new DevModeGuard(env);
        ReflectionTestUtils.setField(guard, "datasourceUrl", datasourceUrl);
        return guard;
    }
}
