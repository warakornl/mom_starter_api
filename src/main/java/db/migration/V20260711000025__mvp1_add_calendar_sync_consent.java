package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * V20260711000025 — Widen {@code consent_record.consent_type} CHECK constraint to include
 * {@code 'calendar_sync'} as the 7th PDPA consent type.
 *
 * <h2>Why a Java migration (not plain SQL)</h2>
 * <p>{@code consent_record.consent_type} was defined with an <em>inline unnamed</em> CHECK
 * constraint in {@code V20260702000012}. PostgreSQL auto-names it
 * {@code consent_record_consent_type_check} (convention: {@code <table>_<col>_check}).
 * H2 (PostgreSQL-mode, used in {@code @DataJpaTest}) auto-names it with a hex counter
 * (e.g. {@code CONSTRAINT_XXXX}) whose exact value depends on the global constraint counter
 * at creation time — non-deterministic from a migration author's perspective.
 *
 * <p>A plain SQL {@code DROP CONSTRAINT IF EXISTS consent_record_consent_type_check} is a
 * <em>no-op</em> on H2 (IF EXISTS silences the "not found" error), leaving the original
 * 6-value constraint active. The subsequent ADD would then co-exist alongside the old unnamed
 * constraint, and H2 would still reject {@code calendar_sync} because the old constraint
 * is never dropped.
 *
 * <p>This Java migration queries {@code INFORMATION_SCHEMA} at runtime to obtain the
 * <em>actual</em> constraint name, drops it, then adds a <em>named</em> 7-value replacement.
 * The query works identically on H2 (PostgreSQL-mode) and real PostgreSQL.
 *
 * <h2>Up semantics</h2>
 * <ol>
 *   <li>Query {@code INFORMATION_SCHEMA} to find the CHECK constraint on
 *       {@code consent_record} whose clause references {@code 'general_health'} (a value
 *       unique to the {@code consent_type} IN-list, distinguishing it from the {@code locale}
 *       CHECK).</li>
 *   <li>DROP the constraint by its actual name (works on both H2 and PostgreSQL).</li>
 *   <li>ADD CONSTRAINT {@code consent_record_consent_type_check} with all 7 values:
 *       the original 6 plus {@code 'calendar_sync'}.</li>
 * </ol>
 * The result is a <em>named</em> constraint. A future 8th type can be widened with
 * {@code DROP CONSTRAINT IF EXISTS consent_record_consent_type_check} safely on both platforms
 * (because the name is now known and consistent).
 *
 * <h2>Everything else is unchanged</h2>
 * Only the {@code consent_type} CHECK constraint is modified. All other schema elements
 * are untouched: append-only model, {@code consent_text_version},
 * {@code granted_at} server-authoritative DEFAULT, {@code locale} CHECK ('th'|'en'),
 * index {@code ix_consent_record__user_type_time}, FK to {@code users(id)},
 * retention / legal-hold policy (consent-slice-design.md §5.3).
 *
 * <h2>PDPA basis</h2>
 * {@code calendar_sync} (7th type) is the explicit consent gate (PDPA ม.26) for writing
 * ANC appointment data to the device-native calendar (expo-calendar, Approach A —
 * client-side only, no server in the data path). Unbundled per ม.19 (not bundled with
 * {@code general_health} or any other consent). Dual-gated with {@code general_health} at
 * the client enforcement layer (calendar-sync-pdpa.md §1.3). Approved by
 * {@code compliance-reviewer} — see {@code docs/compliance/calendar-sync-pdpa.md §1.1}.
 *
 * <h2>Constraint name summary (both platforms)</h2>
 * <ul>
 *   <li><strong>PostgreSQL (before):</strong> auto-name {@code consent_record_consent_type_check}
 *       (6 values, unnamed at DDL time → PostgreSQL convention).</li>
 *   <li><strong>H2 (before):</strong> auto-name {@code CONSTRAINT_XXXX} (6 values,
 *       hex-counter from H2's internal state).</li>
 *   <li><strong>Both (after):</strong> named {@code consent_record_consent_type_check}
 *       (7 values, explicitly named in the ADD CONSTRAINT DDL).</li>
 * </ul>
 *
 * <h2>Down / rollback</h2>
 * Flyway Community Edition has no undo. If rollback is required, apply a new migration:
 * <pre>{@code
 * -- Step 1: remove any calendar_sync rows to prevent constraint rejection during rollback
 * DELETE FROM consent_record WHERE consent_type = 'calendar_sync';
 *
 * -- Step 2: swap the constraint back to the original 6 values
 * ALTER TABLE consent_record
 *     DROP CONSTRAINT IF EXISTS consent_record_consent_type_check;
 * ALTER TABLE consent_record
 *     ADD CONSTRAINT consent_record_consent_type_check
 *     CHECK (consent_type IN (
 *         'general_health', 'sensitive_lab_results', 'pdf_egress',
 *         'infant_feeding', 'cloud_storage', 'child_health'
 *     ));
 * }</pre>
 * WARNING: rolling back destroys any {@code calendar_sync} grant/withdrawal audit evidence
 * (PDPA ม.26 concern). Roll forward instead if any real consent rows exist.
 *
 * <h2>H2 compatibility verified</h2>
 * <ul>
 *   <li>No jsonb — no h2-masks-jsonb-binding risk (house lesson).</li>
 *   <li>INFORMATION_SCHEMA query qualified with table aliases on all shared columns
 *       (avoids H2's "Ambiguous column name" error on unqualified {@code CONSTRAINT_NAME}).</li>
 *   <li>Runtime constraint-name discovery — portable across both platforms.</li>
 *   <li>ADD CONSTRAINT with explicit name — works on H2 PostgreSQL-mode and real PostgreSQL.</li>
 *   <li>Verified: {@code @DataJpaTest} on H2 passes including {@code calendar_sync} insert
 *       and invalid-type rejection assertions.</li>
 * </ul>
 */
public class V20260711000025__mvp1_add_calendar_sync_consent extends BaseJavaMigration {

    // New 7-value IN-list (original 6 + calendar_sync)
    private static final String IN_LIST_7 =
            "'general_health', 'sensitive_lab_results', 'pdf_egress', " +
            "'infant_feeding', 'cloud_storage', 'child_health', 'calendar_sync'";

    // Explicit name given to the replacement constraint so future widening
    // can use DROP CONSTRAINT IF EXISTS by this known name on both platforms.
    private static final String CONSTRAINT_NAME = "consent_record_consent_type_check";

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();

        // ── Step 1: find the actual consent_type CHECK constraint name ────────────────────────
        // H2 auto-named the constraint CONSTRAINT_XXXX; PostgreSQL named it
        // consent_record_consent_type_check.  We query INFORMATION_SCHEMA at runtime so
        // this migration is correct on both platforms without hard-coding either name.
        String existing = findConsentTypeConstraintName(conn);

        // ── Step 2: drop the 6-value constraint ──────────────────────────────────────────────
        // Skip if not found (guards against a partial prior run that already dropped it).
        if (existing != null) {
            String dropDdl = "ALTER TABLE consent_record DROP CONSTRAINT " + quoteId(existing);
            try (PreparedStatement ps = conn.prepareStatement(dropDdl)) {
                ps.execute();
            }
        }

        // ── Step 3: add the widened 7-value named constraint ─────────────────────────────────
        String addDdl = "ALTER TABLE consent_record " +
                "ADD CONSTRAINT " + CONSTRAINT_NAME + " " +
                "CHECK (consent_type IN (" + IN_LIST_7 + "))";
        try (PreparedStatement ps = conn.prepareStatement(addDdl)) {
            ps.execute();
        }
    }

    /**
     * Queries {@code INFORMATION_SCHEMA} to find the CHECK constraint on
     * {@code consent_record} that guards the {@code consent_type} column.
     *
     * <p>Detection strategy: the check clause contains {@code 'general_health'} (one of the
     * consent type values), which is absent from the {@code locale} constraint
     * ({@code 'th'|'en'}). Using {@code LIKE '%GENERAL_HEALTH%'} after {@code UPPER()}
     * matches both H2's stored form ({@code "CONSENT_TYPE" IN('general_health', ...)})
     * and PostgreSQL's normalised form
     * ({@code ((consent_type)::text = ANY (ARRAY['general_health'::text, ...]))}).
     *
     * <p>All column references are fully qualified with table aliases to avoid H2's
     * "Ambiguous column name CONSTRAINT_NAME" error when joining two
     * {@code INFORMATION_SCHEMA} views that both expose that column.
     *
     * @param conn active JDBC connection (not auto-closed — owned by Flyway)
     * @return constraint name as stored in INFORMATION_SCHEMA, or {@code null} if not found
     */
    private String findConsentTypeConstraintName(Connection conn) throws Exception {
        String sql =
                "SELECT tc.CONSTRAINT_NAME " +
                "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS AS tc " +
                "INNER JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS AS cc " +
                "    ON  tc.CONSTRAINT_CATALOG = cc.CONSTRAINT_CATALOG " +
                "    AND tc.CONSTRAINT_SCHEMA  = cc.CONSTRAINT_SCHEMA " +
                "    AND tc.CONSTRAINT_NAME    = cc.CONSTRAINT_NAME " +
                "WHERE UPPER(tc.TABLE_NAME)   = 'CONSENT_RECORD' " +
                "  AND tc.CONSTRAINT_TYPE     = 'CHECK' " +
                "  AND UPPER(cc.CHECK_CLAUSE) LIKE '%GENERAL_HEALTH%' " +
                "LIMIT 1";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * Double-quotes a SQL identifier (standard quoting for both H2 and PostgreSQL).
     * Any embedded double-quotes are escaped by doubling them.
     *
     * @param identifier the raw identifier string from INFORMATION_SCHEMA
     * @return {@code "<identifier>"} (safely quoted)
     */
    private static String quoteId(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
