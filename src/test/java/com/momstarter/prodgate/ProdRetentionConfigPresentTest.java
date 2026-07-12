package com.momstarter.prodgate;

import com.momstarter.MomStarterApiApplication;
import com.momstarter.sync.TombstoneGcScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Launch-gate #4 (real hard-erasure) — build-time CI assertion.
 *
 * <p>Per deploy-pipeline-and-cloud-options.md §1.4 row 4: today Gate #4 is
 * "manual-checklist-only ... the weakest of the 5 gates" because nothing at build-time
 * asserts that {@code momstarter.retention.days} / {@code legal-hold-days} / {@code gc.cron}
 * are actually configured under the prod profile, or that the tombstone GC scheduler bean
 * exists. This test closes that gap: it boots the real prod-profile context (same approach as
 * {@link ProdProfileRejectsDevBeansTest}) and asserts all three values resolve to non-null/
 * non-blank, AND that {@link TombstoneGcScheduler} — the bean that actually drives the
 * {@code @Scheduled} daily sweep — is present in the context (proving scheduling is wired,
 * not merely configured in yaml with nothing consuming it).
 *
 * <p>This test FAILS if any of the three properties is absent/blank under {@code prod}, or if
 * the scheduler bean is missing — it does not rely on manual review of the yaml file.
 */
class ProdRetentionConfigPresentTest {

    private ConfigurableApplicationContext context;

    private static Map<String, String> baseSystemProps(String schemaName) {
        Map<String, String> props = new HashMap<>();
        props.put("spring.datasource.url",
                "jdbc:h2:mem:" + schemaName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        props.put("spring.datasource.username", "sa");
        props.put("spring.datasource.password", "");
        props.put("spring.flyway.enabled", "false");
        props.put("server.port", "0");
        return props;
    }

    private static void setSystemProps(Map<String, String> props) {
        props.forEach(System::setProperty);
    }

    private static void clearSystemProps(Map<String, String> props) {
        props.keySet().forEach(System::clearProperty);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void prodProfileHasRetentionLegalHoldAndGcCronConfiguredAndSchedulerWired() {
        Map<String, String> props = baseSystemProps("gate4main");
        setSystemProps(props);
        try {
            context = new SpringApplicationBuilder(MomStarterApiApplication.class,
                    ProdProfileRejectsDevBeansTest.TestStubConfig.class)
                    .web(WebApplicationType.SERVLET)
                    .profiles("prod")
                    .run();

            Environment env = context.getEnvironment();
            String retentionDays = env.getProperty("momstarter.retention.days");
            String legalHoldDays = env.getProperty("momstarter.retention.legal-hold-days");
            String gcCron = env.getProperty("momstarter.gc.cron");

            assertThat(retentionDays)
                    .as("momstarter.retention.days must be set under prod profile")
                    .isNotBlank();
            assertThat(legalHoldDays)
                    .as("momstarter.retention.legal-hold-days must be set under prod profile")
                    .isNotBlank();
            assertThat(gcCron)
                    .as("momstarter.gc.cron must be set under prod profile")
                    .isNotBlank();

            // Scheduler bean itself must be wired (proves the GC sweep is actually scheduled,
            // not just configured in yaml with nothing consuming the values).
            TombstoneGcScheduler scheduler = context.getBean(TombstoneGcScheduler.class);
            assertThat(scheduler).isNotNull();
        } finally {
            clearSystemProps(props);
        }
    }

    /**
     * Non-vacuousness proof: if {@code momstarter.gc.cron} were absent/blank under prod (e.g. a
     * future refactor removes it from application-prod.yml), this test demonstrates a real gap
     * IS caught rather than silently ignored. It forces a genuinely blank value using a system
     * property (the same override mechanism the main test itself uses to beat profile-yaml
     * precedence — see {@link ProdProfileRejectsDevBeansTest} class javadoc for why
     * {@code .properties(...)} on {@link SpringApplicationBuilder} cannot be used for this: it
     * registers as Boot's LOWEST-precedence "default properties" source and would be silently
     * overridden by {@code application-prod.yml}'s real value, which is exactly the
     * false-negative failure mode this test exists to rule out).
     *
     * <p>A blank cron actually breaks {@code @Scheduled} bean post-processing outright (Spring
     * refuses to parse an empty cron expression) — so the observable here is "context fails to
     * start," an even stronger signal of a real gap than a blank-string read. Either way, a
     * config gap in {@code momstarter.gc.cron} can never silently boot a working-looking prod
     * context with no GC scheduling — which is exactly what Gate #4 needs to guarantee.
     */
    @Test
    void configGapWouldBeCaughtEvenThoughJavaCodeHasFallbackDefaults() {
        Map<String, String> props = baseSystemProps("gate4gap");
        props.put("momstarter.gc.cron", ""); // blank override — simulates a real config gap
        setSystemProps(props);
        try {
            SpringApplicationBuilder builder = new SpringApplicationBuilder(
                    MomStarterApiApplication.class, ProdProfileRejectsDevBeansTest.TestStubConfig.class)
                    .web(WebApplicationType.SERVLET)
                    .profiles("prod");

            assertThrows(Exception.class, builder::run,
                    "a blank momstarter.gc.cron must be detected as a config gap "
                            + "(context must fail to start rather than silently boot with no GC "
                            + "scheduling)");
        } finally {
            clearSystemProps(props);
        }
    }
}
