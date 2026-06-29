package com.momstarter.auth.dto;

import java.time.Instant;

/**
 * One "device signed in" row (§D) — the active leaf of a refresh-token family.
 * Never carries token material. {@code current} marks the calling device (MVP: always
 * false until the caller's device identity is wired — flagged follow-up).
 */
public record DeviceSession(
        String deviceId,
        String deviceName,
        Instant createdAt,
        Instant lastSeenAt,
        boolean current) {
}
