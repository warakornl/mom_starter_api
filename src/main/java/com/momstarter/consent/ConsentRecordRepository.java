package com.momstarter.consent;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ConsentRecord}.
 *
 * <p>Access patterns:
 * <ol>
 *   <li><strong>ConsentChecker hot path</strong> — {@link #findLatestGranted}: single-row
 *       native query ordered by {@code (granted_at DESC, granted ASC, id DESC)} to determine
 *       current consent state; fail-safe tiebreak (withdrawal wins concurrent same-timestamp
 *       rows). Covered by {@code ix_consent_record__user_type_time}.</li>
 *   <li><strong>GET /account/consents (no cursor)</strong> — {@link #findByUserIdOrderByGrantedAtDescIdDesc}:
 *       keyset first-page scan ordered DESC for the consent history UI.</li>
 *   <li><strong>GET /account/consents (with cursor)</strong> — {@link #findByUserIdBeforeCursor}:
 *       cursor-continuation keyset scan using the {@code (granted_at, id)} position of the
 *       last-seen row.</li>
 * </ol>
 *
 * <p>All queries scope by {@code user_id} to enforce ownership (IDOR prevention).
 */
public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    // -------------------------------------------------------------------------
    // ConsentChecker hot path
    // -------------------------------------------------------------------------

    /**
     * Returns the {@code granted} value of the latest consent record for the given
     * {@code (userId, consentType)} pair, applying the deterministic fail-safe tiebreak:
     * {@code ORDER BY granted_at DESC, granted ASC, id DESC LIMIT 1}.
     *
     * <p>Semantics:
     * <ul>
     *   <li>{@code Optional.of(true)}  — latest row is a grant  → consent active</li>
     *   <li>{@code Optional.of(false)} — latest row is a withdrawal → consent revoked</li>
     *   <li>{@code Optional.empty()}   — no row exists → user has never consented (fail-closed)</li>
     * </ul>
     *
     * <p>The {@code granted ASC} tiebreak puts {@code false} (withdrawal) before {@code true}
     * (grant) when two rows share the same {@code granted_at} timestamp — withdrawal wins ties,
     * which is the fail-safe behavior mandated by PDPA ม.19(ค).
     *
     * <p>Uses a native query to express the exact multi-column ORDER BY that matches the
     * {@code ix_consent_record__user_type_time} index — JPQL cannot express the mixed-direction
     * sort needed for the full tiebreak semantics.
     *
     * @param userId      authenticated user id
     * @param consentType one of the 6 PDPA consent-type strings
     * @return the {@code granted} flag of the latest row, or empty if no row exists
     */
    @Query(value = """
            SELECT granted FROM consent_record
            WHERE user_id = :userId AND consent_type = :consentType
            ORDER BY granted_at DESC, granted ASC, id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<Boolean> findLatestGranted(@Param("userId") UUID userId,
                                        @Param("consentType") String consentType);

    // -------------------------------------------------------------------------
    // GET /account/consents — history list, first page
    // -------------------------------------------------------------------------

    /**
     * Returns consent records for {@code userId} ordered by {@code (granted_at DESC, id DESC)},
     * most recent first.  Used for the first page of {@code GET /account/consents} (no cursor).
     *
     * <p>Pass {@code Pageable.ofSize(limit + 1)} to detect whether a next page exists.
     *
     * @param userId   authenticated user id
     * @param pageable page size (caller passes {@code limit + 1} to check for next page)
     * @return records in DESC order, up to {@code pageable.getPageSize()} rows
     */
    List<ConsentRecord> findByUserIdOrderByGrantedAtDescIdDesc(UUID userId, Pageable pageable);

    // -------------------------------------------------------------------------
    // GET /account/consents — history list, cursor continuation
    // -------------------------------------------------------------------------

    /**
     * Cursor-continuation keyset scan for {@code GET /account/consents}.
     *
     * <p>Resumes after the last row of the previous page using the row-value predicate
     * {@code (granted_at, id) < (cursorGrantedAt, cursorId)} in DESC order.
     *
     * <p>Pass {@code Pageable.ofSize(limit + 1)} to detect whether a further next page exists.
     *
     * @param userId           authenticated user id
     * @param cursorGrantedAt  {@code granted_at} of the last row on the previous page
     * @param cursorId         {@code id} of the last row on the previous page
     * @param pageable         page size (caller passes {@code limit + 1} to check for next page)
     * @return records after the cursor in DESC order, up to {@code pageable.getPageSize()} rows
     */
    @Query("SELECT c FROM ConsentRecord c WHERE c.userId = :userId " +
           "AND (c.grantedAt < :cursorGrantedAt " +
           "     OR (c.grantedAt = :cursorGrantedAt AND c.id < :cursorId)) " +
           "ORDER BY c.grantedAt DESC, c.id DESC")
    List<ConsentRecord> findByUserIdBeforeCursor(@Param("userId") UUID userId,
                                                  @Param("cursorGrantedAt") Instant cursorGrantedAt,
                                                  @Param("cursorId") UUID cursorId,
                                                  Pageable pageable);

    // -------------------------------------------------------------------------
    // PDPA ม.30/31 — data export (full audit history, chronological)
    // -------------------------------------------------------------------------

    /**
     * Returns the full consent audit history for the given user, ordered chronologically
     * ({@code granted_at ASC, id ASC}). Used exclusively by the {@code GET /account/export}
     * path (PDPA ม.30/31 portability).
     *
     * <p>All rows are returned (no pagination) since the total number of consent events
     * per user is naturally bounded (6 consent types × small number of changes each).
     *
     * @param userId the authenticated user's id (IDOR scope enforced by the service)
     * @return all consent records in chronological order
     */
    @Query("SELECT c FROM ConsentRecord c WHERE c.userId = :userId " +
           "ORDER BY c.grantedAt ASC, c.id ASC")
    List<ConsentRecord> findAllByUserIdForExport(@Param("userId") UUID userId);
}
