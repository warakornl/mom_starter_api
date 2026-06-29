package com.momstarter.auth;

import com.momstarter.auth.dto.DeviceSession;
import com.momstarter.error.ApiException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Opaque, rotating refresh tokens with reuse-detection (§A/§B).
 *
 * <p>The raw token (256-bit CSPRNG) is returned to the caller but only its SHA-256 is
 * ever stored. Every rotation mints a new token and consumes the presented one; replaying
 * an already-rotated token is treated as theft and burns the entire device family.
 */
@Service
public class RefreshTokenService {

    static final Duration REFRESH_TTL = Duration.ofDays(30);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository tokens;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /** Raw token plus its absolute expiry and owning user — returned to the caller, never persisted as-is. */
    public record Issued(String rawToken, Instant expiresAt, UUID userId) {
    }

    /** Start a brand-new family (a session-minting event: login / verify-email). */
    public Issued mintFamily(UUID userId, String deviceId, String deviceName) {
        String raw = generateRawToken();
        RefreshToken rt = new RefreshToken();
        rt.setUserId(userId);
        rt.setTokenHash(sha256Hex(raw));
        rt.setFamilyId(UUID.randomUUID());
        rt.setDeviceId(deviceId);
        rt.setDeviceName(deviceName);
        rt.setExpiresAt(now().plus(REFRESH_TTL));
        tokens.save(rt);
        return new Issued(raw, rt.getExpiresAt(), userId);
    }

    /** Rotate the presented token, detecting reuse. */
    public Issued rotate(String rawToken, String deviceId) {
        RefreshToken presented = tokens.findByTokenHash(sha256Hex(rawToken))
                .orElseThrow(() -> new ApiException(401, "invalid_token"));

        if (presented.getRevokedAt() != null) {
            // A revoked token was presented. If it had been rotated away (has a successor),
            // this is a replay = theft -> burn the whole family. Otherwise it was a logout /
            // family-revoke (no successor) -> simply invalid.
            if (tokens.existsByPreviousId(presented.getId())) {
                revokeFamily(presented.getFamilyId());
                throw new ApiException(401, "token_reuse_detected");
            }
            throw new ApiException(401, "invalid_token");
        }

        if (presented.getExpiresAt().isBefore(now())) {
            throw new ApiException(401, "invalid_token");
        }

        String raw = generateRawToken();
        RefreshToken next = new RefreshToken();
        next.setUserId(presented.getUserId());
        next.setTokenHash(sha256Hex(raw));
        next.setFamilyId(presented.getFamilyId());
        next.setDeviceId(deviceId != null ? deviceId : presented.getDeviceId());
        next.setDeviceName(presented.getDeviceName());
        next.setPreviousId(presented.getId());
        next.setExpiresAt(now().plus(REFRESH_TTL));
        tokens.save(next);

        presented.setRevokedAt(now());
        presented.setLastSeenAt(now());
        tokens.save(presented);

        return new Issued(raw, next.getExpiresAt(), presented.getUserId());
    }

    /** Revoke every still-active token in a device family (theft response / single-device logout). */
    public void revokeFamily(UUID familyId) {
        Instant t = now();
        for (RefreshToken rt : tokens.findByFamilyId(familyId)) {
            if (rt.getRevokedAt() == null) {
                rt.setRevokedAt(t);
                tokens.save(rt);
            }
        }
    }

    /** Revoke the family of every token bound to a given device (sign out that one device). */
    public void revokeDevice(UUID userId, String deviceId) {
        for (RefreshToken rt : tokens.findByUserId(userId)) {
            if (deviceId.equals(rt.getDeviceId()) && rt.getRevokedAt() == null) {
                revokeFamily(rt.getFamilyId());
            }
        }
    }

    /** Revoke every family for a user (logout allDevices / password reset). */
    public void revokeAllForUser(UUID userId) {
        Instant t = now();
        for (RefreshToken rt : tokens.findByUserId(userId)) {
            if (rt.getRevokedAt() == null) {
                rt.setRevokedAt(t);
                tokens.save(rt);
            }
        }
    }

    /** Revoke the family that owns the presented raw token (logout of one session). */
    public void revokeByRawToken(String rawToken) {
        tokens.findByTokenHash(sha256Hex(rawToken))
                .ifPresent(rt -> revokeFamily(rt.getFamilyId()));
    }

    /** The user's "devices signed in" — one row per family with a still-active (unrevoked, unexpired) leaf. */
    public List<DeviceSession> listSessions(UUID userId) {
        Instant now = now();
        Map<UUID, RefreshToken> activeByFamily = new LinkedHashMap<>();
        for (RefreshToken rt : tokens.findByUserId(userId)) {
            if (rt.getRevokedAt() != null || rt.getExpiresAt().isBefore(now)) {
                continue;
            }
            activeByFamily.put(rt.getFamilyId(), rt);
        }
        List<DeviceSession> sessions = new ArrayList<>();
        for (RefreshToken rt : activeByFamily.values()) {
            sessions.add(new DeviceSession(rt.getDeviceId(), rt.getDeviceName(),
                    rt.getCreatedAt(), rt.getLastSeenAt(), false));
        }
        return sessions;
    }

    private Instant now() {
        return clock.instant();
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32]; // 256-bit
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    public static String sha256Hex(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
