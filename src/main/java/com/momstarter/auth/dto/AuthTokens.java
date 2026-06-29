package com.momstarter.auth.dto;

/**
 * The 200 body of /auth/login, /auth/refresh and /auth/verify-email.
 * accessToken = short-lived RS256 JWT; refreshToken = opaque random (server stores only its SHA-256).
 */
public record AuthTokens(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresIn,
        long refreshTokenExpiresIn) {
}
