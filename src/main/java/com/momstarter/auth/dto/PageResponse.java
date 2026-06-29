package com.momstarter.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Page wrapper for list endpoints — contract N4/N5: list GETs MUST return
 * {@code { items: [...], nextCursor? }} rather than a bare array.
 *
 * <p>MVP: {@code nextCursor} is always {@code null} (full list, no cursor pagination);
 * it is excluded from the JSON response when absent so the shape stays compatible with
 * a future paginated implementation (clients check for the field's presence, not a
 * sentinel value).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageResponse<T>(
        List<T> items,
        String nextCursor) {

    /** Convenience factory — no pagination cursor (full result). */
    public static <T> PageResponse<T> of(List<T> items) {
        return new PageResponse<>(items, null);
    }
}
