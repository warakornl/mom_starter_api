package com.momstarter.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Atomic compare-and-set consume (BE-CORE-6). Sets {@code consumed_at} in a single UPDATE
     * statement only when the token is not yet consumed and has not expired.
     *
     * <p>Returns 1 when the update succeeded (this thread "won"), 0 when the token was already
     * consumed, expired, or simply not found. The 0 case maps uniformly to
     * {@code 410 reset_token_invalid} — no TOCTOU window.
     *
     * <p>{@code clearAutomatically = true} flushes the first-level cache after the bulk UPDATE so
     * that a subsequent {@code findByTokenHash} reads fresh state from the database.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE PasswordResetToken t SET t.consumedAt = :now " +
           "WHERE t.tokenHash = :hash AND t.consumedAt IS NULL AND t.expiresAt > :now")
    int atomicConsume(@Param("hash") String hash, @Param("now") Instant now);
}
