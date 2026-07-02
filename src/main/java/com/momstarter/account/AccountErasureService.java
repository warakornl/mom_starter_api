package com.momstarter.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Hard-purges soft-deleted user accounts and ALL their FK-child rows past the retention window.
 *
 * <p>Closes the "account-level hard-erasure" gap (PDPA ม.33 prod-gate blocker D) flagged in
 * {@code consent-hardgate-erasure-design.md §2.6}. Called daily by
 * {@link com.momstarter.sync.TombstoneGcScheduler}, after
 * {@link com.momstarter.sync.TombstoneGcService#purgeExpiredTombstones(int)} has already
 * cleaned up individual collection tombstones.
 *
 * <h2>FK-safe cascade order</h2>
 * <p>Every child table listed in {@link #CHILD_DELETE_ORDER} has
 * {@code user_id REFERENCES users(id) ON DELETE RESTRICT} (the PostgreSQL default).
 * Attempting to delete the {@code users} row before its FK children would raise a
 * constraint-violation exception. {@code CHILD_DELETE_ORDER} lists every child table in
 * dependency order (no inter-child FK constraints exist between these tables); the
 * {@code users} row is deleted last.
 *
 * <h2>reminder_occurrences and reminders</h2>
 * <p>{@code reminder_occurrences.reminder_id} is a SOFT LINK (no FK constraint — OQ-CAL-6
 * orphan tolerance). Deleting {@code reminders} before or after {@code reminder_occurrences}
 * is therefore safe from a DB constraint perspective. Both are deleted before {@code users}.
 *
 * <h2>LEGAL-PENDING — consent_record retention window</h2>
 * <p>Migration {@code V20260702000012} and design §2.6 contemplate a <em>~1-year</em>
 * legal-hold window for {@code consent_record} rows after account erasure: the consent audit
 * trail should survive the 180-day tombstone GC and be purged separately by a dedicated
 * legal-hold GC at ~1 year. <strong>Thai legal counsel must confirm</strong> whether
 * {@code consent_record} must be retained beyond 180 days before this goes to production.
 * <br>
 * The current implementation deletes {@code consent_record} at the same 180-day threshold
 * (necessary for FK constraint compliance — {@code consent_record.user_id RESTRICT} cannot
 * be satisfied while the {@code users} row remains). If legal counsel requires ~1-year hold:
 * <ol>
 *   <li>180-day GC pass: delete health-data and auth-token children; do NOT delete
 *       {@code consent_record}; do NOT delete the {@code users} row (just leave it
 *       soft-deleted until the legal-hold GC runs).</li>
 *   <li>~1-year GC pass (separate service/scheduler): delete {@code consent_record} first,
 *       then the {@code users} row.</li>
 * </ol>
 *
 * <h2>Retention window</h2>
 * <p>{@code retentionDays} (default 180) is read from {@code momstarter.retention.days} by
 * the caller. <strong>180 days is LEGAL-PENDING</strong> — Thai legal counsel must confirm
 * this value satisfies PDPA storage limitation before production launch
 * (consent-hardgate-erasure-design.md §2.3 / §3.2).
 *
 * <h2>Multi-pod note</h2>
 * <p>Concurrent runs across multiple pods are safe (idempotent DELETE WHERE queries), but
 * cause duplicate work. Add ShedLock before horizontal scaling (deferred — design §2.2).
 */
@Service
public class AccountErasureService {

    private static final Logger log = LoggerFactory.getLogger(AccountErasureService.class);

    /**
     * FK-safe child-table deletion order.
     *
     * <p>Every entry is a table whose {@code user_id} column is a FK referencing
     * {@code users(id) ON DELETE RESTRICT}. Entries are ordered so no row in a later table
     * is a FK parent of a row in an earlier table (there are no inter-entry FK constraints).
     * The {@code users} row itself is NOT listed here — it is deleted separately after all
     * child rows have been removed.
     *
     * <p><strong>LEGAL-PENDING:</strong> {@code consent_record} is deleted in this pass.
     * See class Javadoc for the legal-hold design alternative.
     */
    static final List<String> CHILD_DELETE_ORDER = List.of(
            "consent_record",            // FK → users; LEGAL-PENDING (see class Javadoc)
            "auth_identity",             // FK → users (federated provider link)
            "password_reset_token",      // FK → users (single-use password-reset tokens)
            "email_verification_token",  // FK → users (email verification tokens)
            "refresh_token",             // FK → users (rotating refresh-token families)
            "pregnancy_profile",         // FK → users; health data (PDPA ม.26)
            "supply_items",              // FK → users (non-health sync collection)
            "reminders",                 // FK → users; health collection (PDPA ม.26)
            "reminder_occurrences",      // FK → users; reminder_id is a SOFT LINK (OQ-CAL-6)
            "checklist_items",           // FK → users; health collection (PDPA ม.26)
            "kick_count_session"         // FK → users; health data (PDPA ม.26)
    );

    private final JdbcTemplate jdbc;

    AccountErasureService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Hard-purges every soft-deleted user account whose {@code users.deleted_at} is older
     * than {@code retentionDays} days, along with ALL FK-child rows.
     *
     * <p>For each eligible user: deletes all child rows in {@link #CHILD_DELETE_ORDER}
     * (children-before-parent, FK-safe), then deletes the {@code users} row itself.
     *
     * <p>Runs inside a single {@link Transactional} scope. A mid-sweep failure rolls
     * back the entire transaction — no partial deletions. Suitable for MVP (small dataset);
     * consider per-user inner transactions for large-scale deployments.
     *
     * <p>Idempotent: re-running after a successful purge returns 0 (no eligible rows found).
     *
     * <p>No personal data is written to logs — only row counts and the retention threshold.
     *
     * @param retentionDays soft-deleted users older than this threshold are eligible for purge
     * @return number of user accounts hard-purged in this run
     */
    @Transactional
    public int purgeExpiredAccounts(int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        Timestamp cutoffTs = Timestamp.from(cutoff);

        // Find all soft-deleted users past the retention window.
        // Table name is a literal — no SQL injection risk.
        List<UUID> eligibleIds = jdbc.queryForList(
                "SELECT id FROM users WHERE deleted_at IS NOT NULL AND deleted_at < ?",
                UUID.class,
                cutoffTs
        );

        if (eligibleIds.isEmpty()) {
            return 0;
        }

        log.info("Account hard-erasure: {} account(s) eligible (deleted_at older than {} days)",
                eligibleIds.size(), retentionDays);

        int purgedCount = 0;
        for (UUID userId : eligibleIds) {
            // Delete children first in FK-safe order (every child table has RESTRICT).
            // Child table names are from the compile-time constant — no SQL injection risk.
            for (String childTable : CHILD_DELETE_ORDER) {
                jdbc.update("DELETE FROM " + childTable + " WHERE user_id = ?", userId);
            }
            // Delete the users row last — all FK constraints are now satisfied.
            int deleted = jdbc.update("DELETE FROM users WHERE id = ?", userId);
            if (deleted > 0) {
                purgedCount++;
            }
        }

        log.info("Account hard-erasure complete: {} account(s) hard-purged", purgedCount);
        return purgedCount;
    }
}
