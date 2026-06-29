package com.momstarter.error;

/**
 * A domain error that maps to a specific HTTP status and a stable, machine-readable
 * snake_case {@code code} (the only thing returned in the body — never a leaky message).
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
