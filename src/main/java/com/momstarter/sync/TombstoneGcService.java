package com.momstarter.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Central tombstone GC sweep service — database-reviewer follow-up #3.
 *
 * <p>Every sync collection that uses soft-delete tombstones MUST have its table name
 * enumerated in {@link #PURGE_TABLES}. This is the single authoritative list of tables
 * subject to tombstone retention policy. Adding a new collection without updating this
 * list is a compliance gap (PDPA K-6 / database-schema §4.4 retention window).
 *
 * <h3>Why native JDBC DELETE (not JPA)</h3>
 * <p>Tombstone purge runs in a scheduled maintenance window. Loading entities into the
 * JPA session to then delete them would consume N entity objects in memory.
 * A direct {@code DELETE WHERE deleted_at < ?} touches only the index — O(1) memory.
 *
 * <h3>Table-name safety</h3>
 * <p>Table names are sourced exclusively from the compile-time constant
 * {@link #PURGE_TABLES}; no user input ever reaches the SQL string.
 * SQL injection via the table-name slot is therefore impossible.
 *
 * <h3>Retention window</h3>
 * <p>Default TTL is 180 days (database-schema §4.4). The caller (scheduler or admin
 * endpoint) supplies {@code retentionDays} so the TTL is configurable per environment
 * without a code change.
 */
@Service
public class TombstoneGcService {

    private static final Logger log = LoggerFactory.getLogger(TombstoneGcService.class);

    /**
     * Central tombstone GC sweep list — the ENUMERATED set of tables that carry
     * a {@code deleted_at} column and are subject to the tombstone retention policy.
     *
     * <p><strong>database-reviewer follow-up #3:</strong> {@code kick_count_session}
     * is explicitly listed here — not merely referenced in a comment.
     *
     * <p>When adding a new sync collection, ALWAYS add its table name here.
     * Omitting a table means tombstones accumulate indefinitely — a PDPA violation.
     */
    static final List<String> PURGE_TABLES = List.of(
            "supply_items",
            "reminders",
            "reminder_occurrences",
            "checklist_items",
            "kick_count_session",      // K-6 retention — enumerated here per DB-reviewer #3
            "pregnancy_profile",       // Phase 3 (hard-erasure prod-gate) — blocker B:
                                       // EDD/birth_date are the most sensitive fields (PDPA ม.26).
                                       // Soft-delete logic already exists (put() resurrect path);
                                       // entries older than retention window are NEVER resurrectable
                                       // (policy: >180 days = intent to erase permanently, ม.33).
            "expenses",                // expenses slice — non-health personal-financial data.
                                       // Tombstone GC ensures deleted expenses are hard-purged after
                                       // the 180-day retention window (database-schema §4.4 / PDPA ม.33).
            "self_log",                // F2 fix (Task 5): self_log — SD-5 health metrics
                                       // (weight, BP, swelling, lochia, symptom). Tombstone GC
                                       // ensures deleted health records are hard-purged after the
                                       // 180-day retention window. Covers consent-withdrawal tombstones
                                       // (PDPA ม.33 / ruling 2.2 / database-schema §4.4).
            "medication_log",          // Slice 2 Task 5: SD-2 health data — dose-event records.
                                       // noteCipher is crypto-shredded on tombstone (§4.4(A)).
                                       // GC ensures tombstoned logs are hard-purged after 180 days
                                       // (PDPA ม.33). Listed before medication_plan in PURGE_TABLES
                                       // for consistency with TIER1_CHILD_DELETE_ORDER FK order,
                                       // though the GC DELETE is per-table and FK-independent.
            "medication_plan"          // Slice 2 Task 5: SD-2 health data — medication schedules.
                                       // nameCipher + doseCipher are crypto-shredded on tombstone
                                       // (§4.4(A)). GC ensures tombstoned plans are hard-purged
                                       // after 180 days (PDPA ม.33).
    );

    private final JdbcTemplate jdbc;

    TombstoneGcService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the explicitly enumerated set of tables subject to tombstone GC.
     *
     * <p>Exposed as a public method so tests can assert that every expected table
     * (including {@code kick_count_session}) is present without depending on the
     * private constant directly.
     *
     * @return unmodifiable list of table names (compile-time constant)
     */
    public List<String> purgeTableNames() {
        return PURGE_TABLES;
    }

    /**
     * Deletes tombstoned rows from every sync table whose {@code deleted_at} is
     * older than {@code retentionDays} days from now.
     *
     * <p>Iterates {@link #PURGE_TABLES} in order. Each table gets a single
     * bulk {@code DELETE} statement; no entity objects are loaded into memory.
     *
     * @param retentionDays tombstone TTL in days (use 180 for production per DB-schema §4.4)
     * @return total number of rows deleted across all tables
     */
    @Transactional
    public int purgeExpiredTombstones(int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        Timestamp cutoffTs = Timestamp.from(cutoff);

        int total = 0;
        for (String table : PURGE_TABLES) {
            // Table name is from a compile-time constant — no SQL injection risk.
            int deleted = jdbc.update(
                    "DELETE FROM " + table + " WHERE deleted_at IS NOT NULL AND deleted_at < ?",
                    cutoffTs
            );
            // Log per-table count for accountability (PDPA ม.37) — no personal data logged.
            if (deleted > 0) {
                log.info("Tombstone GC: purged {} row(s) from {} (cutoff={})", deleted, table, cutoff);
            }
            total += deleted;
        }
        return total;
    }
}
