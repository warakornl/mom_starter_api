package com.momstarter.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Contract error body shape: {@code { code, message, details? }}.
 *
 * <p>{@code code} is machine-readable snake_case; {@code message} is a generic,
 * non-sensitive human string mapped from the code — never raw exception messages that
 * could leak stack traces, emails, or schema details. {@code details} is excluded from
 * the JSON response when absent (e.g., for non-validation errors).
 *
 * <p>All auth-surface codes are enumeration-safe (C7): they never reveal whether an
 * email/account exists; the message must not add that information either.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
        String code,
        String message,
        List<String> details) {
}
