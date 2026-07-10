package com.momstarter.pregnancy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link PregnancyProfile}.
 *
 * <p>Primary lookup is by {@code userId}: the service layer calls
 * {@link #findByUserIdAndDeletedAtIsNull} for live reads ({@code GET /pregnancy-profile},
 * {@code PUT /pregnancy-profile}) and {@link #findByUserId} when it also needs to see
 * the tombstone (pull-replication change-set, PDPA erasure checks).
 */
public interface PregnancyProfileRepository extends JpaRepository<PregnancyProfile, UUID> {

    /**
     * Returns the live (non-deleted) profile for the given user, or empty if the user has
     * no profile or it has been soft-deleted.
     *
     * <p>Used by {@code GET /pregnancy-profile} and {@code PUT /pregnancy-profile}.
     * The unique index on {@code user_id} guarantees at most one result.
     */
    Optional<PregnancyProfile> findByUserIdAndDeletedAtIsNull(UUID userId);

    /**
     * Returns any profile for the given user, including a soft-deleted tombstone.
     *
     * <p>Used by pull-replication (sync/pull change-set) and tombstone-propagation logic so
     * deleted profiles are still delivered to other devices.
     */
    Optional<PregnancyProfile> findByUserId(UUID userId);

    /**
     * Per-row cipher-NULL shred for ALL cipher columns on {@code pregnancy_profile}
     * (PDPA ม.33 §4.4(A)).
     *
     * <p>Sets ALL five cipher columns to {@code NULL} for the profile owned by
     * {@code userId}:
     * <ul>
     *   <li>{@code mother_first_name_cipher} — identity-PII name (V20260707000018)</li>
     *   <li>{@code mother_last_name_cipher} — identity-PII name (V20260707000018)</li>
     *   <li>{@code baby_name_cipher} — identity-PII name (V20260707000018)</li>
     *   <li>{@code hospital_admission_date_cipher} — delivery-record health date (V20260710000019)</li>
     *   <li>{@code hospital_discharge_date_cipher} — delivery-record health date (V20260710000019)</li>
     * </ul>
     *
     * <p>This is the DB-side primitive for the per-row crypto-shred that must execute on
     * the profile tombstone path (the belt-and-suspenders PDPA T0 evidence that all
     * cipher bytes are explicitly removed from the row before the 180d hard-purge carries
     * away the tombstone).
     *
     * <p>Note: {@code delivery_type_cipher} and {@code birth_note} are written to
     * {@code pregnancy_profile} but managed by the JPA entity's setter path on the
     * tombstone (set to {@code null} before {@code deleted_at} is written) — they are
     * NOT included here because they are mapped entity fields and the service layer nulls
     * them directly.  These five columns are native-SQL–only targets because
     * {@code springboot-backend-dev} maps them in a subsequent step.
     *
     * <p><strong>springboot-backend-dev MUST call this method</strong> in the same UPDATE
     * that sets {@code deleted_at} on the profile row (inside a {@code @Transactional}
     * context) — analogous to {@code MedicationPlanSyncCollection.applyDelete()} which
     * calls {@code plan.setNameCipher(null)} before setting {@code deleted_at}.
     *
     * <p>Uses native SQL so it works independently of JPA entity field mapping
     * (the entity fields for the five columns are added by {@code springboot-backend-dev}
     * in the same or a follow-on slice).
     *
     * <p>Idempotent: if the profile row does not exist or the columns are already {@code NULL},
     * the UPDATE is a no-op (0 rows affected).
     *
     * <p><strong>MUST be called within a transaction</strong> — participates in the caller's
     * existing {@code @Transactional} context.
     *
     * <p>{@code clearAutomatically = true}: evicts the {@link PregnancyProfile} entity from the
     * Hibernate L1 cache after the native UPDATE so a subsequent
     * {@link JpaRepository#findById} re-reads from the DB rather than returning stale
     * cached bytes.
     *
     * @param userId the user whose profile cipher columns should be nulled
     * @return number of rows updated (0 if no profile exists; 1 if shred was applied)
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE pregnancy_profile "
            + "SET mother_first_name_cipher = NULL, "
            +     "mother_last_name_cipher = NULL, "
            +     "baby_name_cipher = NULL, "
            +     "hospital_admission_date_cipher = NULL, "
            +     "hospital_discharge_date_cipher = NULL "
            + "WHERE user_id = :userId",
            nativeQuery = true)
    int shredCiphersByUserId(@Param("userId") UUID userId);
}
