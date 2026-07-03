package com.momstarter.account;

import com.momstarter.account.dto.export.AccountExportResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for {@code GET /v1/account/export} (PDPA ม.30/31 data portability).
 *
 * <h2>Purpose</h2>
 * <p>Returns the authenticated user's complete personal data as a single machine-readable
 * JSON document. This fulfils the user's right to access their data (ม.30) and to receive
 * it in a portable, structured format (ม.31).
 *
 * <h2>Auth and IDOR prevention</h2>
 * <p>The user id is read EXCLUSIVELY from the JWT subject ({@code Jwt.getSubject()}).
 * No request parameter, path variable, or body id is accepted — this eliminates the
 * insecure direct object reference (IDOR) vector entirely. Spring Security's resource-server
 * filter enforces Bearer-JWT authentication before the method is invoked ({@code .anyRequest().authenticated()}).
 *
 * <h2>No consent gate</h2>
 * <p>This endpoint is deliberately NOT gated behind the {@link com.momstarter.pregnancy.ConsentChecker}.
 * Accessing one's own personal data is a legal right (PDPA ม.30), not a consented purpose.
 * A consent gate here would be legally incorrect — it would allow the controller to deny
 * users access to data the controller holds on them, which PDPA prohibits.
 *
 * <h2>Response</h2>
 * <ul>
 *   <li>{@code 200 OK} — {@code Content-Type: application/json};
 *       {@code Content-Disposition: attachment; filename="export.json"} (signals to browsers
 *       that the response should be saved as a file)</li>
 *   <li>{@code 401 Unauthorized} — missing or invalid Bearer token (Spring Security)</li>
 *   <li>{@code 404 not_found} — user absent or soft-deleted (consistent with GET /account)</li>
 * </ul>
 *
 * <h2>Soft-delete behaviour</h2>
 * <p>Returns {@code 404} when the account has been soft-deleted ({@code deleted_at IS NOT NULL}).
 * This is consistent with the rest of the account surface ({@code GET /account} has the same
 * guard). Users wishing to export data before deletion should do so before calling
 * {@code DELETE /account}. Within the 15-minute access-token window after deletion this
 * endpoint returns 404, which is an acceptable trade-off for MVP (PDPA ม.30/31 does not
 * require export to be available after the user has initiated their own deletion).
 */
@RestController
@RequestMapping("/account")
public class AccountExportController {

    private final AccountExportService exportService;

    public AccountExportController(AccountExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * Assembles and returns the authenticated user's complete personal data export.
     *
     * <p>The {@code Content-Disposition: attachment; filename="export.json"} header is set
     * so that web clients (browser-based) automatically prompt to save the file rather than
     * rendering it inline. This is a usability convention for data-download endpoints and is
     * not a security control.
     *
     * @param jwt the verified JWT injected by Spring Security's resource-server filter;
     *            the subject ({@code jwt.getSubject()}) is the sole source of the user id
     * @return 200 with the complete export body, or 404 if the user is absent/soft-deleted
     */
    @GetMapping("/export")
    public ResponseEntity<AccountExportResponse> export(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        AccountExportResponse response = exportService.export(userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"export.json\"")
                .body(response);
    }
}
