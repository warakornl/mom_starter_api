package com.momstarter.auth.dto;

/**
 * Logout body. {@code refreshToken} revokes that one device family; {@code allDevices=true}
 * revokes every family for the subject. Both are optional.
 */
public record LogoutRequest(
        String refreshToken,
        Boolean allDevices) {
}
