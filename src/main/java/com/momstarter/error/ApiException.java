package com.momstarter.error;

/**
 * A domain error that maps to a specific HTTP status and a stable, machine-readable
 * snake_case {@code code} (the only thing returned in the body — never a leaky message).
 *
 * <p>An optional {@code details} string may be attached for errors that the contract
 * specifies must carry additional context — e.g. {@code 403 consent_required} carries
 * {@code details: "general_health"} so the client knows which consent gate blocked the
 * request (api-contract "Consent gating — health-data processing").
 *
 * <p>When {@code details} is {@code null} the field is excluded from the JSON response
 * by the {@link Problem} record's {@code @JsonInclude(NON_NULL)} annotation.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String details;

    /** Constructs an exception with no additional {@code details} value (most errors). */
    public ApiException(int status, String code) {
        this(status, code, null);
    }

    /**
     * Constructs an exception with an optional {@code details} value.
     *
     * @param details machine-readable context string included in the Problem body, or
     *                {@code null} if not applicable
     */
    public ApiException(int status, String code, String details) {
        super(code);
        this.status  = status;
        this.code    = code;
        this.details = details;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** Returns the optional details value, or {@code null} if not set. */
    public String getDetails() {
        return details;
    }
}
