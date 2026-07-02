package com.momstarter.consent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response body for {@code GET /account/consents}.
 *
 * <p>Returns a cursor-paginated list of consent records ordered by
 * {@code granted_at DESC, id DESC} (most recent first).
 *
 * <p>{@code nextCursor} is {@code null} when there are no further pages;
 * the field is excluded from the JSON response in that case
 * ({@code @JsonInclude(NON_NULL)}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsentListResponse(
        List<ConsentResponse> items,
        String nextCursor
) {
}
