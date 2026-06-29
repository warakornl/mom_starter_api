package com.momstarter.pregnancy;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
