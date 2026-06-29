package com.momstarter.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterRequest(
        @NotBlank String email,
        @NotBlank String password,
        String locale,
        String deviceId) {
}
