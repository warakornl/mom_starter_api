package com.momstarter.account;

import com.momstarter.account.dto.DekResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for {@code GET /v1/account/dek} — DEK login-delivery endpoint
 * (ADR Decision 2.2, field-encryption Scheme A Phase-1 sub-slice a).
 *
 * <h2>Purpose</h2>
 * <p>Delivers the authenticated account's plaintext DEK (Base64) to the device on login
 * or Keychain miss. The device stores it in Keychain ({@code WHEN_UNLOCKED_THIS_DEVICE_ONLY})
 * and wipes it on logout / 401 auto-logout (ADR appsec RULING 4).
 *
 * <h2>IDOR prevention</h2>
 * <p>The user id is read <em>exclusively</em> from {@code Jwt.getSubject()} — the JWT subject
 * injected by Spring Security's resource-server filter. No path variable, query parameter, or
 * body id is accepted. This eliminates the IDOR vector completely.
 *
 * <h2>Security headers</h2>
 * <ul>
 *   <li>{@code Cache-Control: no-store} — prevents the DEK from being cached by any
 *       intermediary (ADR appsec RULING 3).</li>
 *   <li>TLS is enforced at the ALB layer in production; the response is not served over
 *       plain HTTP.</li>
 * </ul>
 *
 * <h2>Response</h2>
 * <ul>
 *   <li>{@code 200 OK} — {@link DekResponse} with Base64 DEK and dekVersion.
 *       <strong>NEVER log the dek field.</strong></li>
 *   <li>{@code 401 Unauthorized} — missing or invalid Bearer token (Spring Security)</li>
 *   <li>{@code 404 dek_not_found} — no {@code account_dek} row for the account
 *       (not yet provisioned, or already crypto-shredded)</li>
 * </ul>
 */
@RestController
@RequestMapping("/account")
public class AccountDekController {

    private final DekService dekService;

    public AccountDekController(DekService dekService) {
        this.dekService = dekService;
    }

    /**
     * Delivers the authenticated account's plaintext DEK as Base64 for Keychain storage.
     *
     * <p>The {@code jwt} parameter is injected by Spring Security's resource-server filter;
     * {@code .anyRequest().authenticated()} ensures this method is unreachable without a
     * valid Bearer token.
     *
     * @param jwt the verified access-token JWT; {@code getSubject()} is the sole source of userId
     * @return 200 with {@link DekResponse}, or 404 if no DEK row exists
     */
    @GetMapping("/dek")
    public ResponseEntity<DekResponse> getDek(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        DekResponse response = dekService.deliverDek(userId);
        // SECURITY: do NOT log response.dek() — it is the plaintext DEK (appsec RULING 5).
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
