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
 * = 03:00 Bangkok ICT, off-peak for Thai users) and delegates to three operations in order:
 * <ol>
 *   <li>{@link TombstoneGcService#purgeExpiredTombstones(int)} — removes soft-delete tombstones
 *       from all sync collections ({@code supply_items, reminders, reminder_occurrences,
 *       checklist_items, kick_count_session, pregnancy_profile}) older than the retention
 *       window. Each table must have a {@code deleted_at} index (added in migration
 *       V20260703000013) for efficient GC at scale.</li>
 *   <li>{@link AccountErasureService#purgeExpiredAccountChildren(int)} — <strong>Tier 1
 *       (180d, health-data pass).</strong> Hard-purges health/auth child rows for soft-deleted
 *       accounts past the retention window. Intentionally retains the {@code users} row and
 *       all {@code consent_record} rows — they must survive until the Tier-2 legal-hold GC
 *       (PDPA ม.37 consent-audit evidence). Closes PDPA ม.33 blocker D
 *       (consent-hardgate-erasure-design.md §2.6).</li>
 *   <li>{@link AccountErasureService#purgeLegalHoldAccounts(int)} — <strong>Tier 2
 *       (~1yr legal-hold pass).</strong> Hard-purges {@code consent_record} THEN {@code users}
 *       (FK-safe order) for accounts whose {@code users.deleted_at} is older than the
 *       legal-hold window ({@code momstarter.retention.legal-hold-days}, default 365d,
 *       LEGAL-PENDING). By the time an account reaches this threshold, all health/auth
 *       children will already have been removed by the Tier-1 pass on prior daily runs.</li>
 * </ol>
 *
 * <h2>Scheduling</h2>
 * <p>Enabled by {@link com.momstarter.config.SchedulingConfig} ({@code @EnableScheduling}).
 * The cron expression is externalized to {@code momstarter.gc.cron} so it can be overridden
 * per environment without a code change (e.g. shorter interval in staging for fast testing).
 *
 * <h2>LEGAL-PENDING — retention windows</h2>
 * <ul>
 *   <li>{@code momstarter.retention.days} (default 180) — Tier-1 health-data cutoff.</li>
 *   <li>{@code momstarter.retention.legal-hold-days} (default 365) — Tier-2 legal-hold cutoff.</li>
 * </ul>
 * <p><strong>Thai legal counsel must confirm BOTH figures</strong> before production launch
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
     * Tier-1 tombstone TTL and health-data child-erasure threshold in days.
     * Sourced from {@code momstarter.retention.days}; defaults to 180.
     *
     * <p><strong>LEGAL-PENDING:</strong> Thai legal counsel must confirm 180 days before prod.
     */
    @Value("${momstarter.retention.days:180}")
    private int retentionDays;

    /**
     * Tier-2 legal-hold threshold in days.
     * Sourced from {@code momstarter.retention.legal-hold-days}; defaults to 365.
     *
     * <p><strong>LEGAL-PENDING:</strong> Thai legal counsel must confirm the exact figure
     * before production launch. 365 is an engineering default, not a legal determination.
     */
    @Value("${momstarter.retention.legal-hold-days:365}")
    private int legalHoldDays;

    private final TombstoneGcService tombstoneGcService;
    private final AccountErasureService accountErasureService;

    TombstoneGcScheduler(TombstoneGcService tombstoneGcService,
                         AccountErasureService accountErasureService) {
        this.tombstoneGcService = tombstoneGcService;
        this.accountErasureService = accountErasureService;
    }

    /**
     * Daily hard-erasure sweep — three-phase.
     *
     * <p>Cron is externalized to {@code momstarter.gc.cron} (default {@code 0 0 20 * * *},
     * i.e. 20:00 UTC = 03:00 Bangkok ICT). Logs are accountability evidence (PDPA ม.37):
     * counts only — no personal data (no email, no EDD, no UUID) is written to logs.
     *
     * <p>Invocation order:
     * <ol>
     *   <li>Collection tombstone GC — clears individually-tombstoned health records.</li>
     *   <li>Tier-1 child erasure — clears health/auth children of soft-deleted accounts;
     *       retains users + consent_record until Tier-2.</li>
     *   <li>Tier-2 legal-hold purge — removes consent_record then users for accounts past
     *       the legal-hold window (FK-safe).</li>
     * </ol>
     */
    @Scheduled(cron = "${momstarter.gc.cron:0 0 20 * * *}")
    public void runDailyPurge() {
        log.info("Hard-erasure daily sweep starting "
                + "(retentionDays={}, legalHoldDays={})", retentionDays, legalHoldDays);

        int tombstoneRows = tombstoneGcService.purgeExpiredTombstones(retentionDays);
        log.info("Tombstone GC complete: {} row(s) purged from sync collections", tombstoneRows);

        int tier1Accounts = accountErasureService.purgeExpiredAccountChildren(retentionDays);
        log.info("Tier-1 child-erasure complete: {} account(s) swept (users+consent_record retained)",
                tier1Accounts);

        int tier2Accounts = accountErasureService.purgeLegalHoldAccounts(legalHoldDays);
        log.info("Tier-2 legal-hold purge complete: {} account(s) hard-purged "
                + "(consent_record+users removed)", tier2Accounts);

        log.info("Hard-erasure daily sweep done: tombstoneRows={} tier1Accounts={} tier2Accounts={}",
                tombstoneRows, tier1Accounts, tier2Accounts);
    }
}
