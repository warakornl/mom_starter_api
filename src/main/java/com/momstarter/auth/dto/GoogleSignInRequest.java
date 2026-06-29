package com.momstarter.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleSignInRequest(
        @NotBlank String idToken,
        @NotBlank String nonce,
        String deviceId) {
}
