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
     * Per-row cipher-NULL shred for the three name cipher columns (PDPA ม.33 §4.4(A)).
     *
     * <p>Sets {@code mother_first_name_cipher}, {@code mother_last_name_cipher}, and
     * {@code baby_name_cipher} to {@code NULL} for the profile owned by {@code userId}.
     * This is the DB-side primitive for the per-row crypto-shred that must execute on
     * the profile tombstone path (the belt-and-suspenders PDPA T0 evidence that the
     * identity-PII name bytes are explicitly removed from the row before the 180d
     * hard-purge carries away the tombstone).
     *
     * <p><strong>springboot-backend-dev MUST call this method</strong> in the same UPDATE
     * that sets {@code deleted_at} on the profile row (inside a {@code @Transactional}
     * context) — analogous to {@code MedicationPlanSyncCollection.applyDelete()} which
     * calls {@code plan.setNameCipher(null)} before setting {@code deleted_at}.
     *
     * <p>Uses native SQL so it works independently of JPA entity field mapping
     * (the entity fields {@code motherFirstNameCipher}, {@code motherLastNameCipher},
     * {@code babyNameCipher} are added by {@code springboot-backend-dev} in the same slice).
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
     * @param userId the user whose profile name cipher columns should be nulled
     * @return number of rows updated (0 if no profile exists; 1 if shred was applied)
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE pregnancy_profile "
            + "SET mother_first_name_cipher = NULL, "
            +     "mother_last_name_cipher = NULL, "
            +     "baby_name_cipher = NULL "
            + "WHERE user_id = :userId",
            nativeQuery = true)
    int shredCiphersByUserId(@Param("userId") UUID userId);
}
