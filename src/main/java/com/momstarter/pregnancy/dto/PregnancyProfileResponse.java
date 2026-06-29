package com.momstarter.pregnancy.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.momstarter.pregnancy.GestationalAge;
import com.momstarter.pregnancy.PregnancyProfile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response body for GET/PUT /pregnancy-profile.
 *
 * <p>Includes all &lt;sync&gt; block fields ({@code id}, {@code version}, {@code createdAt},
 * {@code updatedAt}, {@code deletedAt}) plus the derived gestational-age snapshot computed from
 * {@code edd} + the client's civil "today" ({@code X-Client-Date} or server UTC fallback).
 *
 * <p>Derived fields are computed at request time, never stored in DB (data-model §4 / §3.1).
 * The client overrides the server snapshot with its own on-device computation (OQ-2 — advisory only).
 *
 * <p>{@code deletedAt} is excluded from the JSON output when null ({@code NON_NULL}), consistent
 * with the &lt;sync&gt; "nullable tombstone" pattern.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PregnancyProfileResponse(
        // <sync> block fields
        UUID id,
        UUID userId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,

        // Stored fields
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate edd,
        String eddBasis,
        String lifecycle,

        // Derived gestational-age snapshot (computed from edd + today; never stored)
        int gestationalWeek,
        int gestationalDay,
        long daysRemaining,
        double progress,
        String currentStage,
        boolean deliveryWindowActive) {

    /**
     * Constructs a response from the stored {@link PregnancyProfile} entity and the pre-computed
     * {@link GestationalAge} snapshot.
     */
    public static PregnancyProfileResponse of(PregnancyProfile p, GestationalAge ga) {
        return new PregnancyProfileResponse(
                p.getId(),
                p.getUserId(),
                p.getVersion(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getDeletedAt(),
                p.getEdd(),
                p.getEddBasis(),
                p.getLifecycle(),
                ga.gestationalWeek(),
                ga.gestationalDay(),
                ga.daysRemaining(),
                ga.progress(),
                ga.currentStage(),
                ga.deliveryWindowActive());
    }
}
