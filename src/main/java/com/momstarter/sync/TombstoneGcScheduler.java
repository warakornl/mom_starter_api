package com.momstarter.sync;

import com.momstarter.account.AccountErasureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily off-peak scheduler that drives the Phase 3 hard-erasure prod-gate (PDPA ม.33).
 *
 * <p>Fires once daily at the time configured by {@code momstarter.gc.cron} (default 20:00 UTC
 * = 03:00 Bangkok ICT, off-peak for Thai users) and delegates to:
 * <ol>
 *   <li>{@link TombstoneGcService#purgeExpiredTombstones(int)} — removes soft-delete tombstones
 *       from all sync collections ({@code supply_items, reminders, reminder_occurrences,
 *       checklist_items, kick_count_session, pregnancy_profile}) older than the retention
 *       window. Each table must have a {@code deleted_at} index (added in migration
 *       V20260703000013) for efficient GC at scale.</li>
 *   <li>{@link AccountErasureService#purgeExpiredAccounts(int)} — hard-purges soft-deleted
 *       user accounts (and ALL FK-child rows in dependency order) past the retention window.
 *       Closes PDPA ม.33 blocker D (consent-hardgate-erasure-design.md §2.6).</li>
 * </ol>
 *
 * <h2>Scheduling</h2>
 * <p>Enabled by {@link com.momstarter.config.SchedulingConfig} ({@code @EnableScheduling}).
 * The cron expression is externalized to {@code momstarter.gc.cron} so it can be overridden
 * per environment without a code change (e.g. shorter interval in staging for fast testing).
 *
 * <h2>LEGAL-PENDING — retention window</h2>
 * <p>{@code momstarter.retention.days} (default 180) is the tombstone TTL and account
 * erasure threshold. <strong>Thai legal counsel must confirm</strong> 180 days satisfies
 * PDPA storage limitation (ม.33) before production launch
 * (consent-hardgate-erasure-design.md §2.3 / §3.2).
 *
 * <h2>Admin endpoint — deferred</h2>
 * <p>No separate admin endpoint is exposed at this time. The system has no {@code ROLE_ADMIN}
 * authority model (blocker C in the design). The scheduler is the primary — and for MVP, sole —
 * trigger for the GC sweep (design §2.2 option C). Add admin triggering only once a proper
 * role model is in place ({@code @PreAuthorize("hasRole('ADMIN')")} + provisioning).
 *
 * <h2>Multi-pod note — ShedLock deferred</h2>
 * <p>In a single-pod deployment, this scheduler is correct as-is. In a multi-pod setup all
 * pods fire the cron simultaneously — the DELETE queries are idempotent but duplicate work
 * occurs. Add ShedLock or move to AWS EventBridge before scaling to &gt;1 pod
 * (deferred — design §2.2).
 *
 * @see TombstoneGcService
 * @see AccountErasureService
 * @see com.momstarter.config.SchedulingConfig
 */
@Component
public class TombstoneGcScheduler {

    private static final Logger log = LoggerFactory.getLogger(TombstoneGcScheduler.class);

    /**
     * Tombstone TTL and account-erasure threshold in days.
     * Sourced from {@code momstarter.retention.days}; defaults to 180.
     *
     * <p><strong>LEGAL-PENDING:</strong> Thai legal counsel must confirm 180 days before prod.
     */
    @Value("${momstarter.retention.days:180}")
    private int retentionDays;

    private final TombstoneGcService tombstoneGcService;
    private final AccountErasureService accountErasureService;

    TombstoneGcScheduler(TombstoneGcService tombstoneGcService,
                         AccountErasureService accountErasureService) {
        this.tombstoneGcService = tombstoneGcService;
        this.accountErasureService = accountErasureService;
    }

    /**
     * Daily hard-erasure sweep.
     *
     * <p>Cron is externalized to {@code momstarter.gc.cron} (default {@code 0 0 20 * * *},
     * i.e. 20:00 UTC = 03:00 Bangkok ICT). Logs are accountability evidence (PDPA ม.37):
     * counts only — no personal data (no email, no EDD, no UUID) is written to logs.
     *
     * <p>Invocation order: tombstone GC first (removes collection tombstones), then account
     * erasure (removes soft-deleted user rows and all remaining child data). This ensures the
     * tombstone GC has already cleaned up any individually-tombstoned health records before
     * the account-level CASCADE runs.
     */
    @Scheduled(cron = "${momstarter.gc.cron:0 0 20 * * *}")
    public void runDailyPurge() {
        log.info("Hard-erasure daily sweep starting (retention={} days)", retentionDays);

        int tombstoneRows = tombstoneGcService.purgeExpiredTombstones(retentionDays);
        log.info("Tombstone GC complete: {} row(s) purged from sync collections", tombstoneRows);

        int accounts = accountErasureService.purgeExpiredAccounts(retentionDays);
        log.info("Account erasure complete: {} account(s) hard-purged", accounts);

        log.info("Hard-erasure daily sweep done: tombstoneRows={} accounts={}", tombstoneRows, accounts);
    }
}
