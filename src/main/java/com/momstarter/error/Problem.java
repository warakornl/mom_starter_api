package com.momstarter.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Contract error body shape: {@code { code, message, details? }}.
 *
 * <p>{@code code} is machine-readable snake_case; {@code message} is a generic,
 * non-sensitive human string mapped from the code — never raw exception messages that
 * could leak stack traces, emails, or schema details.
 *
 * <p>{@code details} is polymorphic by design — the api-contract uses it in two ways:
 * <ul>
 *   <li>A <strong>plain string</strong> naming the consent type that blocked the call —
 *       e.g. {@code "general_health"} in a {@code 403 consent_required} response. Serialised
 *       as a JSON string: {@code "details": "general_health"}.</li>
 *   <li>A <strong>list of strings</strong> describing field-level validation failures — the
 *       {@code 422 validation_error} shape. Serialised as a JSON array:
 *       {@code "details": ["field: message", ...]}.</li>
 * </ul>
 * Using {@code Object} here lets Jackson serialise whichever concrete type the handler
 * provides (String → JSON string; List → JSON array) without a custom serialiser.
 *
 * <p>{@code @JsonInclude(NON_NULL)} excludes {@code details} from the JSON response
 * entirely when it is absent — preserving the existing contract for all errors that do
 * not carry additional context.
 *
 * <p>All auth-surface codes are enumeration-safe (C7): they never reveal whether an
 * email/account exists; the message must not add that information either.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
        String code,
        String message,
        Object details) {
}
