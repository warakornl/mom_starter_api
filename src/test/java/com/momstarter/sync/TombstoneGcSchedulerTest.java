package com.momstarter.sync;

import com.momstarter.account.AccountErasureService;
import com.momstarter.config.SchedulingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TombstoneGcScheduler} and {@link SchedulingConfig}.
 *
 * <p>Uses Mockito directly (no Spring context) to avoid starting the scheduler cron
 * automatically during tests. The {@code @Scheduled} annotation is verified via reflection.
 * Delegation to {@link TombstoneGcService} and {@link AccountErasureService} is verified
 * via mock invocation assertions.
 *
 * <p>The {@code retentionDays} field (normally injected via {@code @Value}) is set
 * with {@link ReflectionTestUtils#setField} to 180, matching the production default.
 */
@ExtendWith(MockitoExtension.class)
class TombstoneGcSchedulerTest {

    @Mock private TombstoneGcService tombstoneGcService;
    @Mock private AccountErasureService accountErasureService;

    private TombstoneGcScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TombstoneGcScheduler(tombstoneGcService, accountErasureService);
        // Simulate @Value("${momstarter.retention.days:180}") injection
        ReflectionTestUtils.setField(scheduler, "retentionDays", 180);
    }

    // -------------------------------------------------------------------------
    // @Scheduled annotation wiring
    // -------------------------------------------------------------------------

    @Test
    void runDailyPurge_hasScheduledAnnotationWithCronExpression() throws NoSuchMethodException {
        Scheduled annotation = TombstoneGcScheduler.class
                .getDeclaredMethod("runDailyPurge")
                .getAnnotation(Scheduled.class);

        assertThat(annotation)
                .as("@Scheduled must be present on runDailyPurge()")
                .isNotNull();
        assertThat(annotation.cron())
                .as("cron expression must not be blank")
                .isNotBlank();
    }

    // -------------------------------------------------------------------------
    // @EnableScheduling on SchedulingConfig
    // -------------------------------------------------------------------------

    @Test
    void schedulingConfig_hasEnableSchedulingAnnotation() {
        EnableScheduling annotation = SchedulingConfig.class.getAnnotation(EnableScheduling.class);
        assertThat(annotation)
                .as("@EnableScheduling must be present on SchedulingConfig")
                .isNotNull();
    }

    // -------------------------------------------------------------------------
    // Delegation to TombstoneGcService
    // -------------------------------------------------------------------------

    @Test
    void runDailyPurge_delegatesToTombstoneGcServiceWithConfiguredRetentionDays() {
        when(tombstoneGcService.purgeExpiredTombstones(180)).thenReturn(0);
        when(accountErasureService.purgeExpiredAccounts(180)).thenReturn(0);

        scheduler.runDailyPurge();

        verify(tombstoneGcService).purgeExpiredTombstones(180);
    }

    // -------------------------------------------------------------------------
    // Delegation to AccountErasureService
    // -------------------------------------------------------------------------

    @Test
    void runDailyPurge_delegatesToAccountErasureServiceWithConfiguredRetentionDays() {
        when(tombstoneGcService.purgeExpiredTombstones(180)).thenReturn(0);
        when(accountErasureService.purgeExpiredAccounts(180)).thenReturn(0);

        scheduler.runDailyPurge();

        verify(accountErasureService).purgeExpiredAccounts(180);
    }

    // -------------------------------------------------------------------------
    // Logging — no exception when both services return non-zero counts
    // -------------------------------------------------------------------------

    @Test
    void runDailyPurge_doesNotThrowWhenServicesReturnNonZeroCounts() {
        when(tombstoneGcService.purgeExpiredTombstones(180)).thenReturn(42);
        when(accountErasureService.purgeExpiredAccounts(180)).thenReturn(3);

        // Verify no exception is thrown and both calls were made
        scheduler.runDailyPurge();
        verify(tombstoneGcService).purgeExpiredTombstones(180);
        verify(accountErasureService).purgeExpiredAccounts(180);
    }
}
