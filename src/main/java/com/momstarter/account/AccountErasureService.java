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
 * Two-tier PDPA hard-erasure service for soft-deleted user accounts.
 *
 * <h2>Tier 1 — health-data retention pass (180d)</h2>
 * <p>{@link #purgeExpiredAccountChildren(int)} hard-purges every health/auth child row
 * for accounts whose {@code users.deleted_at} is older than {@code retentionDays} (default
 * 180d). It deliberately <strong>retains</strong> the {@code users} row and all
 * {@code consent_record} rows:
 * <ul>
 *   <li>The {@code users} row is the FK anchor — {@code consent_record.user_id REFERENCES
 *       users(id) ON DELETE RESTRICT}. Deleting {@code users} at 180d would violate this
 *       constraint while consent evidence still exists.</li>
 *   <li>{@code consent_record} is PDPA ม.37 consent-audit evidence. The schema design
 *       ({@code V20260702000012}, §RETENTION) mandates survival until the legal-hold GC
 *       (~1 yr post-erasure).</li>
 * </ul>
 *
 * <h2>Tier 2 — legal-hold purge (~1yr)</h2>
 * <p>{@link #purgeLegalHoldAccounts(int)} hard-purges {@code consent_record} THEN {@code users}
 * (FK-safe order) for accounts whose {@code users.deleted_at} is older than
 * {@code legalHoldDays} (default 365d, LEGAL-PENDING). By the time a user reaches the
 * legal-hold threshold, the 180d Tier-1 pass has already run many times and cleared all
 * health/auth children. Tier-2 only needs to delete the two PDPA-audit rows.
 *
 * <h2>FK-safe cascade order</h2>
 * <p>Tier-1 child tables are listed in {@link #TIER1_CHILD_DELETE_ORDER}. Every entry has
 * {@code user_id REFERENCES users(id) ON DELETE RESTRICT}. Rows must be deleted in this
 * (children-before-parent) order. {@code users} itself is deleted only in Tier-2, after
 * {@code consent_record} is removed first.
 *
 * <h2>reminder_occurrences and reminders</h2>
 * <p>{@code reminder_occurrences.reminder_id} is a SOFT LINK (no FK constraint —
 * OQ-CAL-6 orphan tolerance). Deleting {@code reminders} before {@code reminder_occurrences}
 * is therefore safe from a DB-constraint perspective.
 *
 * <h2>LEGAL-PENDING — legal-hold window</h2>
 * <p>{@code legalHoldDays} defaults to 365. <strong>Thai legal counsel must confirm</strong>
 * whether one year satisfies the PDPA storage-limitation principle (ม.26, ม.33) for
 * consent-record retention before production launch
 * ({@code consent-hardgate-erasure-design.md §2.3 / §3.2}).
 *
 * <h2>Retention windows</h2>
 * <ul>
 *   <li>Tier-1: {@code momstarter.retention.days} (default 180) — LEGAL-PENDING</li>
 *   <li>Tier-2: {@code momstarter.retention.legal-hold-days} (default 365) — LEGAL-PENDING</li>
 * </ul>
 *
 * <h2>Multi-pod note — ShedLock deferred</h2>
 * <p>Concurrent runs across multiple pods are safe (idempotent DELETE WHERE queries) but
 * cause duplicate work. Add ShedLock before horizontal scaling (deferred — design §2.2).
 *
 * @see com.momstarter.sync.TombstoneGcScheduler
 */
@Service
public class AccountErasureService {

    private static final Logger log = LoggerFactory.getLogger(AccountErasureService.class);

    /**
     * Tier-1 FK-safe child-table deletion order.
     *
     * <p>Every entry has {@code user_id REFERENCES users(id) ON DELETE RESTRICT}.
     * Rows in these tables are deleted in the order listed (children-before-parent).
     * Neither {@code consent_record} nor {@code users} appears here — both are retained
     * until the Tier-2 legal-hold GC pass runs.
     *
     * <p>When adding a new health/auth child table, add it here AND add a seed row to
     * {@code AccountErasureServiceTest#purgeExpiredAccountChildren_tier1_cascadesAllChildTablesAndKeepsUserAndConsentRecord}
     * so that a forgotten table causes that test to fail.
     */
    static final List<String> TIER1_CHILD_DELETE_ORDER = List.of(
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
     * <strong>Tier 1 (180d health-data pass).</strong> Hard-purges FK-child health/auth rows
     * for every soft-deleted account whose {@code users.deleted_at} is older than
     * {@code retentionDays} days. The {@code users} row and all {@code consent_record} rows
     * are intentionally <strong>retained</strong> — they are cleaned up by
     * {@link #purgeLegalHoldAccounts(int)} at the legal-hold threshold (~1yr).
     *
     * <p>Tables purged (in FK-safe order): {@code auth_identity}, {@code password_reset_token},
     * {@code email_verification_token}, {@code refresh_token}, {@code pregnancy_profile},
     * {@code supply_items}, {@code reminders}, {@code reminder_occurrences},
     * {@code checklist_items}, {@code kick_count_session}.
     *
     * <p>Runs inside a single {@link Transactional} scope. A mid-sweep failure rolls back
     * the entire transaction — no partial deletions.
     *
     * <p>Idempotent: re-running after a successful sweep finds the same eligible users
     * (they are still soft-deleted) and deletes 0 child rows (already gone on the first run).
     *
     * <p>No personal data is written to logs — only row counts and the retention threshold.
     *
     * @param retentionDays soft-deleted users older than this threshold are eligible
     * @return number of soft-deleted user accounts whose children were swept in this run
     */
    @Transactional
    public int purgeExpiredAccountChildren(int retentionDays) {
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

        log.info("Tier-1 child-erasure: {} account(s) eligible (deleted_at older than {} days)",
                eligibleIds.size(), retentionDays);

        for (UUID userId : eligibleIds) {
            // Delete children in FK-safe order.
            // Child table names are from the compile-time constant — no SQL injection risk.
            // consent_record and users are intentionally NOT in TIER1_CHILD_DELETE_ORDER.
            for (String childTable : TIER1_CHILD_DELETE_ORDER) {
                jdbc.update("DELETE FROM " + childTable + " WHERE user_id = ?", userId);
            }
        }

        log.info("Tier-1 child-erasure complete: health/auth children purged for {} account(s); "
                + "users + consent_record rows retained for legal-hold GC", eligibleIds.size());
        return eligibleIds.size();
    }

    /**
     * <strong>Tier 2 (legal-hold pass, ~1yr).</strong> Hard-purges {@code consent_record}
     * THEN {@code users} (FK-safe order) for soft-deleted accounts whose
     * {@code users.deleted_at} is older than {@code legalHoldDays} days.
     *
     * <p>By the time an account reaches the legal-hold threshold (default 365d), the
     * Tier-1 pass ({@link #purgeExpiredAccountChildren}) will have already run on every
     * prior daily sweep — all health/auth child rows are gone. Tier-2 only needs to remove
     * the two PDPA-audit rows: {@code consent_record} (first, FK-safe) then {@code users}.
     *
     * <p>FK order proof: {@code consent_record.user_id REFERENCES users(id) ON DELETE RESTRICT}.
     * Deleting {@code consent_record} before {@code users} satisfies the constraint. Reversing
     * the order would raise a {@code DataIntegrityViolationException}.
     *
     * <p>Runs inside a single {@link Transactional} scope — mid-sweep failure rolls back.
     *
     * <p>Idempotent: re-running after a successful purge finds no eligible users → returns 0.
     *
     * <p>No personal data is written to logs.
     *
     * @param legalHoldDays soft-deleted users older than this threshold are eligible for Tier-2 purge
     * @return number of user accounts hard-purged in this run
     */
    @Transactional
    public int purgeLegalHoldAccounts(int legalHoldDays) {
        Instant cutoff = Instant.now().minus(legalHoldDays, ChronoUnit.DAYS);
        Timestamp cutoffTs = Timestamp.from(cutoff);

        // Find all soft-deleted users past the legal-hold window.
        List<UUID> eligibleIds = jdbc.queryForList(
                "SELECT id FROM users WHERE deleted_at IS NOT NULL AND deleted_at < ?",
                UUID.class,
                cutoffTs
        );

        if (eligibleIds.isEmpty()) {
            return 0;
        }

        log.info("Tier-2 legal-hold purge: {} account(s) eligible (deleted_at older than {} days)",
                eligibleIds.size(), legalHoldDays);

        int purgedCount = 0;
        for (UUID userId : eligibleIds) {
            // Delete consent_record FIRST — it holds FK → users(id) ON DELETE RESTRICT.
            // Deleting users before consent_record would violate the constraint.
            jdbc.update("DELETE FROM consent_record WHERE user_id = ?", userId);

            // Delete the users row last — all FK constraints are now satisfied.
            int deleted = jdbc.update("DELETE FROM users WHERE id = ?", userId);
            if (deleted > 0) {
                purgedCount++;
            }
        }

        log.info("Tier-2 legal-hold purge complete: {} account(s) hard-purged "
                + "(consent_record + users)", purgedCount);
        return purgedCount;
    }
}
