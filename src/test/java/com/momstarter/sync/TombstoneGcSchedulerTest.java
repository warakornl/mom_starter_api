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
 *
 * <h2>Two-tier delegation</h2>
 * <p>The daily cron now dispatches to THREE operations in order:
 * <ol>
 *   <li>{@link TombstoneGcService#purgeExpiredTombstones(int)} — collection tombstone GC.</li>
 *   <li>{@link AccountErasureService#purgeExpiredAccountChildren(int)} — Tier-1 (180d):
 *       purges health/auth child rows; retains users + consent_record.</li>
 *   <li>{@link AccountErasureService#purgeLegalHoldAccounts(int)} — Tier-2 (~1yr):
 *       purges consent_record THEN users (FK-safe) for accounts past the legal-hold window.</li>
 * </ol>
 *
 * <p>The {@code retentionDays} field (normally injected via {@code @Value}) is set with
 * {@link ReflectionTestUtils#setField} to 180, and {@code legalHoldDays} is set to 365,
 * matching the production defaults.
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
        // Simulate @Value("${momstarter.retention.legal-hold-days:365}") injection
        ReflectionTestUtils.setField(scheduler, "legalHoldDays", 365);
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
        when(accountErasureService.purgeExpiredAccountChildren(180)).thenReturn(0);
        when(accountErasureService.purgeLegalHoldAccounts(365)).thenReturn(0);

        scheduler.runDailyPurge();

        verify(tombstoneGcService).purgeExpiredTombstones(180);
    }

    // -------------------------------------------------------------------------
    // Delegation to AccountErasureService Tier-1: purgeExpiredAccountChildren
    // -------------------------------------------------------------------------

    @Test
    void runDailyPurge_delegatesToPurgeExpiredAccountChildrenWithRetentionDays() {
        when(tombstoneGcService.purgeExpiredTombstones(180)).thenReturn(0);
        when(accountErasureService.purgeExpiredAccountChildren(180)).thenReturn(0);
        when(accountErasureService.purgeLegalHoldAccounts(365)).thenReturn(0);

        scheduler.runDailyPurge();

        verify(accountErasureService).purgeExpiredAccountChildren(180);
    }

    // -------------------------------------------------------------------------
    // Delegation to AccountErasureService Tier-2: purgeLegalHoldAccounts
    // -------------------------------------------------------------------------

    @Test
    void runDailyPurge_delegatesToPurgeLegalHoldAccountsWithLegalHoldDays() {
        when(tombstoneGcService.purgeExpiredTombstones(180)).thenReturn(0);
        when(accountErasureService.purgeExpiredAccountChildren(180)).thenReturn(0);
        when(accountErasureService.purgeLegalHoldAccounts(365)).thenReturn(0);

        scheduler.runDailyPurge();

        verify(accountErasureService).purgeLegalHoldAccounts(365);
    }

    // -------------------------------------------------------------------------
    // Logging — no exception when all services return non-zero counts
    // -------------------------------------------------------------------------

    @Test
    void runDailyPurge_doesNotThrowWhenServicesReturnNonZeroCounts() {
        when(tombstoneGcService.purgeExpiredTombstones(180)).thenReturn(42);
        when(accountErasureService.purgeExpiredAccountChildren(180)).thenReturn(3);
        when(accountErasureService.purgeLegalHoldAccounts(365)).thenReturn(1);

        scheduler.runDailyPurge();

        verify(tombstoneGcService).purgeExpiredTombstones(180);
        verify(accountErasureService).purgeExpiredAccountChildren(180);
        verify(accountErasureService).purgeLegalHoldAccounts(365);
    }
}
