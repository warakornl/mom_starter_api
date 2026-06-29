package com.momstarter.pregnancy;

import com.momstarter.pregnancy.dto.PregnancyProfileResponse;

/**
 * Thrown by {@link PregnancyProfileService} when the {@code If-Match} version header
 * does not match the current record version (api-contract B2, OQ-5).
 *
 * <p>The controller catches this and returns HTTP 409 Conflict with the current
 * authoritative record in the body, so the client can re-pull, re-apply its intent,
 * and retry.
 */
public class StaleVersionException extends RuntimeException {

    private final PregnancyProfileResponse currentProfile;

    public StaleVersionException(PregnancyProfileResponse currentProfile) {
        super("If-Match version does not match the current record version");
        this.currentProfile = currentProfile;
    }

    public PregnancyProfileResponse getCurrentProfile() {
        return currentProfile;
    }
}
