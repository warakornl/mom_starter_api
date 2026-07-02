package com.momstarter.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * Maps every {@link ApiException} and Bean-Validation failure to a
 * {@code Problem { code, message, details? }} body — contract error shape, never leaking
 * raw exception messages or stack-trace fragments.
 *
 * <p>Message strings are generic and non-sensitive: they are mapped from the error
 * {@code code} only, never from any user-supplied input or internal state (C7/§H — auth
 * surface codes must never reveal email / account existence).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Generic, non-sensitive message for each domain code. */
    private static final Map<String, String> CODE_MESSAGES = Map.ofEntries(
            Map.entry("invalid_credentials",   "Invalid email or password."),
            Map.entry("rate_limited",          "Too many requests. Please try again later."),
            Map.entry("token_reuse_detected",  "Session invalid. Please sign in again."),
            Map.entry("invalid_token",         "Invalid or expired token."),
            Map.entry("verify_token_invalid",  "Verification link is invalid or expired."),
            Map.entry("reset_token_invalid",   "Reset link is invalid or expired."),
            Map.entry("email_unverified",      "Email address must be verified before proceeding."),
            Map.entry("consent_required",      "Consent is required before proceeding."),
            Map.entry("google_token_invalid",  "Google sign-in token is invalid."),
            Map.entry("link_required",         "An account with this email already exists."),
            Map.entry("identity_in_use",       "This Google account is already linked to another user."),
            Map.entry("last_credential",       "Cannot remove the last sign-in method."),
            Map.entry("password_too_short",    "Password does not meet minimum length requirements."),
            Map.entry("password_breached",     "Password has been found in a known data breach."),
            Map.entry("validation_error",      "One or more fields are invalid."),
            // Pregnancy-profile / optimistic-concurrency codes
            Map.entry("not_found",                "The requested resource was not found."),
            Map.entry("precondition_required",    "If-Match header is required for this operation."),
            Map.entry("precondition_failed",      "If-Match header value is invalid or unrecognised."),
            // Birth-event lifecycle codes (api-contract §409 invalid_lifecycle_state)
            Map.entry("invalid_lifecycle_state",  "The profile lifecycle state does not allow this operation."),
            // Offline-sync engine error codes (api-contract "Offline-sync engine (PINNED)")
            Map.entry("batch_too_large",          "Batch exceeds maximum allowed size (1000 records or 5 MB)."),
            Map.entry("invalid_cursor",           "Continuation cursor is invalid or has expired."),
            Map.entry("watermark_expired",        "Watermark is too old. A full resync is required.")
            // NOTE: "account_deleted" entry removed (vestigial — nothing throws it).
            // The bounded ≤15-min access-token window after soft-delete is an accepted
            // MVP trade-off: refresh is revoked on DELETE /account, so the window cannot
            // be extended. See AccountService.deleteAccount Javadoc (PDPA s.33 note).
    );

    private static String messageFor(String code) {
        return CODE_MESSAGES.getOrDefault(code, "An unexpected error occurred.");
    }

    /**
     * All domain errors (ApiException) → Problem body with generic, code-mapped message.
     *
     * <p>When the exception carries a {@code details} string (e.g. consent gate errors
     * include the consent-type name per api-contract) it is forwarded to the Problem body;
     * otherwise {@code details} is {@code null} and excluded from the JSON by
     * {@code @JsonInclude(NON_NULL)}.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Problem> handleApiException(ApiException ex) {
        Problem body = new Problem(ex.getCode(), messageFor(ex.getCode()), ex.getDetails());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * Bean-Validation failures ({@code @Valid} on {@code @RequestBody}) → 422 validation_error.
     *
     * <p>Contract: 422 + {@code { code: "validation_error", message, details[] }}. Field names and
     * constraint descriptions are included in {@code details} because they describe the submitted
     * value's structure, not account existence — safe to surface.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    if (error instanceof FieldError fe) {
                        return fe.getField() + ": " + fe.getDefaultMessage();
                    }
                    return error.getObjectName() + ": " + error.getDefaultMessage();
                })
                .sorted() // deterministic order for byte-identical comparisons in tests
                .toList();

        Problem body = new Problem("validation_error", messageFor("validation_error"), details);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }
}
