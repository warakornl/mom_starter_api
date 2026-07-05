package com.momstarter.account;

import com.momstarter.account.dto.DekResponse;
import com.momstarter.encryption.KmsClient;
import com.momstarter.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * DEK lifecycle service for field-encryption Scheme A (ADR Decision 2).
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>{@link #provisionDek(UUID)} — generates a fresh per-account DEK via KMS and persists
 *       the wrapped blob. Called during account creation (see {@code RegistrationService}).
 *       <strong>The KMS call is outside any DB transaction</strong> (ADR IMPORTANT-4).</li>
 *   <li>{@link #deliverDek(UUID)} — unwraps the stored DEK via KMS and returns it as a
 *       Base64 string for delivery to the device over TLS (login-delivery endpoint).</li>
 * </ol>
 *
 * <h2>IMPORTANT-4 — KMS outside the DB transaction</h2>
 * <p>{@link #provisionDek} is deliberately <em>not</em> annotated {@code @Transactional}.
 * The KMS {@code GenerateDataKey} call (step A) must complete before any DB write begins
 * (step B). The INSERT uses {@code ON CONFLICT DO NOTHING} for idempotency; the
 * {@code JdbcTemplate.update()} call creates its own minimal single-statement transaction.
 *
 * <h2>Security invariants (appsec RULING 5)</h2>
 * <ul>
 *   <li>The plaintext DEK ({@code GeneratedDek.plaintextDek()}) is NEVER logged or stored.</li>
 *   <li>The wrapped DEK ({@code AccountDek.wrappedDek}) is NEVER returned in any API response
 *       (only the plaintext DEK is delivered, transiently, via {@link #deliverDek}).</li>
 *   <li>After {@link #deliverDek} returns, the plaintext DEK bytes are GC-eligible.</li>
 * </ul>
 */
@Service
public class DekService {

    private static final Logger log = LoggerFactory.getLogger(DekService.class);

    /**
     * INSERT ... ON CONFLICT DO NOTHING template (idempotent provisioning, ADR Decision 2).
     *
     * <p>Conflict target omitted intentionally: {@code account_dek} has exactly one
     * UNIQUE constraint (the PK {@code user_id}), so the no-target form is equivalent
     * to {@code ON CONFLICT (user_id) DO NOTHING} in PostgreSQL.  It is also the form
     * accepted by H2 2.2.x (PostgreSQL mode) — H2 does not parse the column-list form.
     * Both databases treat a conflict on the primary key as a no-op.
     *
     * <p>{@code version = 0} matches the Hibernate {@code @Version} {@code DEFAULT 0}
     * convention (see migration design comments).  Timestamps use Java-side
     * {@code Instant.now()} rather than SQL {@code now()} for H2/PostgreSQL portability.
     */
    private static final String INSERT_IF_ABSENT =
            "INSERT INTO account_dek " +
            "(user_id, wrapped_dek, kms_key_id, wrap_context, dek_version, created_at, updated_at, version) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 0) " +
            "ON CONFLICT DO NOTHING";

    private final KmsClient kmsClient;
    private final AccountDekRepository accountDekRepository;
    private final JdbcTemplate jdbc;

    public DekService(KmsClient kmsClient,
                      AccountDekRepository accountDekRepository,
                      JdbcTemplate jdbc) {
        this.kmsClient = kmsClient;
        this.accountDekRepository = accountDekRepository;
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // DEK provisioning
    // -------------------------------------------------------------------------

    /**
     * Provisions a DEK for the account if one does not already exist (idempotent).
     *
     * <p>Step A: calls {@code KMS.GenerateDataKey} <strong>outside any DB transaction</strong>
     * (ADR IMPORTANT-4). This is a blocking network round-trip and must not hold a DB connection.
     *
     * <p>Step B: INSERTs the wrapped DEK with {@code ON CONFLICT DO NOTHING} (idempotent).
     * The loser of a concurrent INSERT race (e.g. two simultaneous verify-email calls for the
     * same account) simply discards its just-minted plaintext DEK — the winner's row is kept.
     *
     * <p>The plaintext DEK returned by KMS is discarded after step B; it is never stored
     * or logged ({@code appsec} RULING 5).
     *
     * @param accountId the account to provision a DEK for
     */
    public void provisionDek(UUID accountId) {
        // Step A — KMS call OUTSIDE any DB transaction
        String accountIdStr = accountId.toString();
        KmsClient.GeneratedDek generated = kmsClient.generateDek(accountIdStr);
        // SECURITY: generated.plaintextDek() is never logged or stored; discard after step B.

        // Step B — INSERT (minimal single-statement transaction, idempotent)
        String wrapContext = "accountId=" + accountIdStr;
        Instant now = Instant.now(); // Java-side timestamp: works in both H2 and PostgreSQL
        jdbc.update(INSERT_IF_ABSENT,
                accountId,
                generated.wrappedDek(),
                generated.kmsKeyId(),
                wrapContext,
                (short) 1,
                Timestamp.from(now),
                Timestamp.from(now));

        log.debug("account_dek provisioned for accountId={}; kmsKeyId={}",
                accountId, generated.kmsKeyId());
        // NOTE: generated.plaintextDek() is NOT logged — appsec RULING 5.
        // NOTE: generated.wrappedDek() is NOT logged — keep wrapped blob out of log files.
    }

    // -------------------------------------------------------------------------
    // DEK login-delivery
    // -------------------------------------------------------------------------

    /**
     * Delivers the plaintext DEK to the authenticated device (login-delivery,
     * {@code GET /v1/account/dek}).
     *
     * <p>Fetches the {@code account_dek} row, calls {@code KMS.Decrypt(wrappedDek,
     * EncryptionContext=accountId)}, and returns the result as a Base64-encoded string over
     * the HTTPS response (see {@code AccountDekController}).
     *
     * <p><strong>SECURITY:</strong> The returned {@link DekResponse#dek()} value is sensitive.
     * The controller MUST NOT log it. The response carries {@code Cache-Control: no-store}.
     *
     * @param accountId the authenticated user's id (from JWT subject — IDOR-safe)
     * @return DEK response with Base64-encoded plaintext DEK and the DEK version
     * @throws ApiException 404 {@code dek_not_found} if no {@code account_dek} row exists
     *                      (either not yet provisioned or already crypto-shredded)
     */
    public DekResponse deliverDek(UUID accountId) {
        AccountDek row = accountDekRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(404, "dek_not_found"));

        // KMS.Decrypt — EncryptionContext binding enforced by KmsClient impl
        byte[] plaintextDek = kmsClient.decryptDek(row.getWrappedDek(), accountId.toString());
        // SECURITY: plaintextDek is never logged — appsec RULING 5.

        String dekBase64 = Base64.getEncoder().encodeToString(plaintextDek);
        // plaintextDek is GC-eligible after Base64 encoding completes.
        // The Base64 string itself is also sensitive — only returned to the authenticated device.

        return new DekResponse(dekBase64, row.getDekVersion());
    }
}
