package com.momstarter.account.dto;

/**
 * Response body for {@code GET /v1/account/dek} (ADR Decision 2.2 login-delivery).
 *
 * <p>The {@code dek} field carries the plaintext DEK as a standard Base64 (RFC 4648, padded)
 * string. The device stores it in Keychain ({@code expo-secure-store}, {@code WHEN_UNLOCKED_THIS_DEVICE_ONLY})
 * and wipes it on logout / 401 (ADR ruling 4).
 *
 * <p><strong>SECURITY: the {@code dek} field is highly sensitive.</strong>
 * <ul>
 *   <li>Never log this value — not in access logs, APM, request-body loggers, or Sentry.</li>
 *   <li>The response must be sent over HTTPS only (enforced by ALB/WAF in production).</li>
 *   <li>The response must not be cached ({@code Cache-Control: no-store} is set by the controller).</li>
 * </ul>
 *
 * @param dek        Base64 (RFC 4648 padded, standard alphabet) encoded 256-bit (32-byte) plaintext DEK.
 *                   <strong>NEVER log this field.</strong>
 * @param dekVersion the DEK generation counter ({@code 1} = current AES-256-GCM / {@code 0x01} envelope).
 *                   Matches {@code account_dek.dek_version}.
 */
public record DekResponse(String dek, int dekVersion) {}
