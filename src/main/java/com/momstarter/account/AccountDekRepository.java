package com.momstarter.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Repository for {@link AccountDek} — per-account KMS-wrapped DEK store.
 *
 * <p>The PK is {@code user_id} (UUID), so standard {@link #findById(Object)} and
 * {@link #existsById(Object)} apply without additional derived methods.
 *
 * <h2>Access patterns</h2>
 * <ul>
 *   <li><strong>Login-delivery</strong>: {@link #findById(Object)} on {@code user_id} to load
 *       the wrapped DEK for {@code KMS.Decrypt}.</li>
 *   <li><strong>Provisioning</strong>: INSERT via {@code JdbcTemplate} (raw SQL with
 *       {@code ON CONFLICT (user_id) DO NOTHING}) in {@code DekService.provisionDek()}.</li>
 *   <li><strong>T0 crypto-shred</strong>: {@link #deleteByUserId(UUID)} in
 *       {@code AccountService.deleteAccount()} immediately after {@code setStatus("deleted")}
 *       (sub-slice c, ADR CRITICAL-1).</li>
 * </ul>
 *
 * <h2>Why @Modifying @Query for delete</h2>
 * <p>Spring Data JPA's derived {@code deleteBy} executes a SELECT then per-entity DELETE.
 * The {@code @Modifying} {@code @Query} generates a single bulk {@code DELETE} statement,
 * which is both more efficient and clearer in the audit log.
 */
public interface AccountDekRepository extends JpaRepository<AccountDek, UUID> {

    /**
     * Hard-deletes the DEK row for the given user. This is the T0 crypto-shred primitive:
     * once this row is gone, {@code KMS.Decrypt(wrappedDek)} becomes impossible, rendering
     * all {@code *_cipher} bytes across every health table irrecoverable.
     *
     * <p><strong>MUST be called within a transaction</strong> (participates in the caller's
     * existing transaction — see {@code @Transactional} on
     * {@code AccountService.deleteAccount()}).
     *
     * <p>Idempotent: if no row exists for {@code userId} (already shredded by T0 or never
     * provisioned), the DELETE is a no-op.
     *
     * @param userId the account whose DEK row should be hard-deleted
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM AccountDek d WHERE d.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
