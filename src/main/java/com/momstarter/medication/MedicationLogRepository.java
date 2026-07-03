package com.momstarter.medication;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link MedicationLog}.
 *
 * <p>Access patterns correspond to three surfaces:
 * <ol>
 *   <li><strong>{@code POST /sync/push} (apply path)</strong> — {@link #findByUserIdAndIdIn}
 *       for batch existence check + immutable-event idempotency; {@link #initVersionToOne}
 *       for the version:=1 create sentinel (api-contract §5).</li>
 *   <li><strong>{@code GET /sync/pull} (pull path)</strong> — safe-window keyset scan
 *       ({@link #findForPull}) and cold-start cursor continuation
 *       ({@link #findForPullAfterCursor}). Includes tombstones so deletions propagate.</li>
 *   <li><strong>Read-only REST — {@code GET /medication-logs}</strong> — live rows only,
 *       keyed on {@link MedicationLog#occurrenceTime} (floating-civil bucket key, FLAG-1 / D5)
 *       with optional {@code from}/{@code to} range, ordered {@code (occurrenceTime DESC, id DESC)};
 *       <strong>plan-agnostic</strong> (no {@code medicationPlanId} filter at the repo level —
 *       the endpoint lists all logs for the user regardless of which plan they are linked to).
 *       ({@link #findForRead} / {@link #findForReadAfterCursor})</li>
 * </ol>
 *
 * <p>MOTHER-health collection; gated by {@code general_health} (per-collection) +
 * {@code cloud_storage} (whole-batch) on {@code sync/push} (spec D6 / §A.1).
 *
 * <p>All queries scope by {@code user_id} to enforce ownership (IDOR prevention, spec D7).
 * Server NEVER predicates over {@link MedicationLog#noteCipher} — opaque {@code bytea}
 * ciphertext (INV-M3 / RULING 1).
 *
 * <p>Index coverage:
 * <ul>
 *   <li>{@code ix_medication_log__sync_pull (user_id, updated_at, id)} — pull path keyset.</li>
 *   <li>{@code ix_medication_log__user_time (user_id, occurrence_time DESC, id DESC)} — read-path
 *       keyset; the composite IS the query order (no sort node in the plan). Explicit DESC per
 *       database-schema §4.2 (contrast self_log's ASC — functional parity, different spelling).</li>
 *   <li>{@code ix_medication_log__plan (medication_plan_id)} — FK-join for plan→logs lookup
 *       and Tier-1 erasure DELETE-by-plan.</li>
 *   <li>{@code ix_medication_log__deleted_at} — tombstone GC sweep.</li>
 * </ul>
 */
public interface MedicationLogRepository extends JpaRepository<MedicationLog, UUID> {

    // -------------------------------------------------------------------------
    // Push path — version initialisation (contract §5 pin: version:=1 on INSERT)
    // -------------------------------------------------------------------------

    /**
     * Atomically bumps {@code version} from {@code 0} to {@code 1} for a newly-inserted entity.
     *
     * <p>Called immediately after {@code saveAndFlush()} on a fresh INSERT so that the
     * wire-visible {@code version} in {@code applied[]} and the DB-stored version are both
     * {@code 1} (api-contract §5: "genuine create → version:=1").
     *
     * <p>{@code clearAutomatically = true} evicts the L1 cache so subsequent loads within
     * the same transaction see the updated value.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE MedicationLog l SET l.version = 1 WHERE l.id = :id AND l.version = 0")
    void initVersionToOne(@Param("id") UUID id);

    // -------------------------------------------------------------------------
    // Push path — batch id lookup
    // -------------------------------------------------------------------------

    /**
     * Returns medication logs owned by {@code userId} whose ids appear in {@code ids}.
     *
     * <p>Used by the sync/push apply path to load existing rows in one query, enabling the
     * immutable-event idempotency check (existing live → no-op; not present → INSERT).
     */
    List<MedicationLog> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // -------------------------------------------------------------------------
    // Pull path — watermark keyset scan (ASC, includes tombstones)
    // -------------------------------------------------------------------------

    /**
     * Steady-state delta pull: medication logs for {@code userId} with
     * {@code updated_at >= since}, ordered {@code (updated_at ASC, id ASC)}.
     *
     * <p>Includes tombstones so deletions propagate to other devices.
     * Covered by {@code ix_medication_log__sync_pull (user_id, updated_at, id)}.
     */
    @Query("SELECT l FROM MedicationLog l " +
           "WHERE l.userId = :userId AND l.updatedAt >= :since " +
           "ORDER BY l.updatedAt ASC, l.id ASC")
    List<MedicationLog> findForPull(@Param("userId") UUID userId,
                                     @Param("since") Instant since,
                                     Pageable pageable);

    /**
     * Cold-start cursor-continuation keyset scan.
     *
     * <p>Resumes a paginated drain after a cursor using the row-value predicate
     * {@code (updated_at, id) > (cursorUpdatedAt, cursorId)}.
     * Covered by {@code ix_medication_log__sync_pull (user_id, updated_at, id)}.
     */
    @Query("SELECT l FROM MedicationLog l " +
           "WHERE l.userId = :userId AND l.updatedAt >= :since " +
           "AND (l.updatedAt > :cursorUpdatedAt " +
           "     OR (l.updatedAt = :cursorUpdatedAt AND l.id > :cursorId)) " +
           "ORDER BY l.updatedAt ASC, l.id ASC")
    List<MedicationLog> findForPullAfterCursor(@Param("userId") UUID userId,
                                                @Param("since") Instant since,
                                                @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                                @Param("cursorId") UUID cursorId,
                                                Pageable pageable);

    // -------------------------------------------------------------------------
    // Read-only REST — GET /medication-logs (live rows, occurrence_time keyset)
    // -------------------------------------------------------------------------

    /**
     * Returns live (non-deleted) medication logs for the given user, optionally filtered by
     * a civil date range on {@link MedicationLog#occurrenceTime} (FLAG-1 / D5).
     *
     * <p>Ordering: {@code (occurrenceTime DESC, id DESC)} — most-recent dose first.
     * Covered by {@code ix_medication_log__user_time (user_id, occurrence_time DESC, id DESC)}.
     *
     * <p><strong>Plan-agnostic</strong>: no {@code medicationPlanId} filter. Returns all logs
     * for the user regardless of which plan they reference (or ad-hoc logs with null planId).
     *
     * <p>{@code null} parameters are treated as open / unfiltered:
     * <ul>
     *   <li>{@code from = null} → no lower bound on {@code occurrence_time}.</li>
     *   <li>{@code to = null} → no upper bound on {@code occurrence_time}.</li>
     * </ul>
     *
     * <p>Server NEVER predicates over {@link MedicationLog#noteCipher} — opaque ciphertext (INV-M3).
     *
     * @param userId   the authenticated user's id (IDOR scope — JWT subject, D7)
     * @param from     lower bound on {@code occurrence_time} (inclusive, nullable → no lower bound)
     * @param to       upper bound on {@code occurrence_time} (inclusive, nullable → no upper bound)
     * @param pageable page size for cursor pagination (default 100, max 500 per spec §A.3)
     * @return live logs in {@code (occurrenceTime DESC, id DESC)} order
     */
    @Query("SELECT l FROM MedicationLog l " +
           "WHERE l.userId = :userId AND l.deletedAt IS NULL " +
           "AND (:from IS NULL OR l.occurrenceTime >= :from) " +
           "AND (:to IS NULL OR l.occurrenceTime <= :to) " +
           "ORDER BY l.occurrenceTime DESC, l.id DESC")
    List<MedicationLog> findForRead(@Param("userId") UUID userId,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to,
                                     Pageable pageable);

    /**
     * Cursor-continuation keyset scan for {@code GET /medication-logs}.
     *
     * <p>Resumes paging after the last record in the previous batch using the row-value
     * predicate {@code (occurrence_time, id) < (cursorOccurrenceTime, cursorId)}
     * (descending keyset).
     *
     * <p>Applies the same {@code from}/{@code to} filters as {@link #findForRead} so the
     * cursor is consistent with the original query.
     */
    @Query("SELECT l FROM MedicationLog l " +
           "WHERE l.userId = :userId AND l.deletedAt IS NULL " +
           "AND (:from IS NULL OR l.occurrenceTime >= :from) " +
           "AND (:to IS NULL OR l.occurrenceTime <= :to) " +
           "AND (l.occurrenceTime < :cursorOccurrenceTime " +
           "     OR (l.occurrenceTime = :cursorOccurrenceTime AND l.id < :cursorId)) " +
           "ORDER BY l.occurrenceTime DESC, l.id DESC")
    List<MedicationLog> findForReadAfterCursor(@Param("userId") UUID userId,
                                                @Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to,
                                                @Param("cursorOccurrenceTime") LocalDateTime cursorOccurrenceTime,
                                                @Param("cursorId") UUID cursorId,
                                                Pageable pageable);

    // -------------------------------------------------------------------------
    // PDPA ม.30/31 — data export (all records, including tombstones)
    // -------------------------------------------------------------------------

    /**
     * Returns ALL medication logs for the given user including soft-deleted tombstones.
     *
     * <p>Used exclusively by the {@code GET /account/export} path (PDPA ม.30/31 portability).
     * Tombstoned rows are included (user's right to access covers the pre-GC window).
     * Ordered by {@code (updated_at ASC, id ASC)} for deterministic output.
     *
     * <p>Server NEVER predicates over {@link MedicationLog#noteCipher} (INV-M3 / G4).
     */
    @Query("SELECT l FROM MedicationLog l WHERE l.userId = :userId " +
           "ORDER BY l.updatedAt ASC, l.id ASC")
    List<MedicationLog> findAllByUserIdForExport(@Param("userId") UUID userId);
}
