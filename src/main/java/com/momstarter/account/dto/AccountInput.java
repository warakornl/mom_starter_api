package com.momstarter.account.dto;

/**
 * PATCH /account request body (api-contract "AccountInput").
 *
 * <p>Both fields are optional. A {@code null} field means "no change to this value".
 *
 * <p>Validation is performed in {@link com.momstarter.account.AccountService}:
 * <ul>
 *   <li>{@code email} — when present: normalized (trim + lowercase); rejected with
 *       {@code 422 validation_error} if the new address is already taken by another user
 *       (non-enumerating — does NOT reveal that the address exists). Email change resets
 *       {@code emailVerified = false}. Re-verification flow is a TODO (mirrors
 *       {@code /auth/register} pattern — out of scope for this slice).</li>
 *   <li>{@code locale} — when present: must be one of {@code th}, {@code en} (contract
 *       enum) → {@code 422 validation_error} if not.</li>
 * </ul>
 *
 * <p>No Bean-Validation annotations are used here so that a missing field (null) is never
 * rejected at the DTO layer; the service distinguishes null ("no change") from a provided
 * value that fails business rules.
 */
public record AccountInput(
        String email,
        String locale
) {}
