package com.momstarter.dev;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-runner tests for ResetTokenExposureGuard @ConditionalOnProperty activation.
 * Profile + datasource guard scenarios are tested directly in ResetTokenExposureGuardTest
 * (unit-level, via ReflectionTestUtils) which is a more reliable approach than trying to
 * set active-profiles in ApplicationContextRunner.
 */
class ResetTokenExposureGuardContextFailTest {

    /** Flag absent → @ConditionalOnProperty suppresses bean → context starts cleanly. */
    @Test
    void flagAbsent_contextStarts_noBeanCreated() {
        new ApplicationContextRunner()
                .withUserConfiguration(ResetTokenExposureGuard.class)
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:test")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(ResetTokenExposureGuard.class);
                });
    }

    /** Flag=false explicitly → bean not created → context starts. */
    @Test
    void flagFalse_contextStarts_noBeanCreated() {
        new ApplicationContextRunner()
                .withUserConfiguration(ResetTokenExposureGuard.class)
                .withPropertyValues(
                        "momstarter.dev.expose-reset-token=false",
                        "spring.datasource.url=jdbc:h2:mem:test")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(ResetTokenExposureGuard.class);
                });
    }

    /**
     * Flag=true → @ConditionalOnProperty activates the guard. Since no 'local' profile is
     * set in ApplicationContextRunner, G2 fires and the context fails.
     * This validates that the guard bean IS activated by the property, and it rejects startup.
     */
    @Test
    void flagTrue_noLocalProfile_guardActivatesAndRejectsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(ResetTokenExposureGuard.class)
                .withPropertyValues(
                        "momstarter.dev.expose-reset-token=true",
                        "spring.datasource.url=jdbc:h2:mem:test")
                .run(ctx -> {
                    // Guard bean was created (flag=true activates it) but @PostConstruct threw
                    // because no 'local' profile → context failed
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure().getCause())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("expose-reset-token");
                });
    }
}
