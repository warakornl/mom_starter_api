package com.momstarter.occurrence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Deterministic ReminderOccurrence id (FLAG-1/N6/N7).
 * id = uuidv5(OCCURRENCE_NAMESPACE, reminderId + "|" + scheduledLocalCivil)
 * The namespace is frozen and byte-identical on iOS/Android/server.
 */
public final class OccurrenceId {

    /** Frozen namespace constant — never per-device. */
    public static final UUID NAMESPACE = UUID.fromString("4328078f-6339-4c38-a2ce-eabff6cbf387");

    private OccurrenceId() {}

    /** scheduledLocalCivil must be the minute-precision floating civil string "YYYY-MM-DDTHH:mm". */
    public static UUID compute(String reminderId, String scheduledLocalCivil) {
        return v5(NAMESPACE, reminderId + "|" + scheduledLocalCivil);
    }

    /** RFC 4122 version-5 (SHA-1) UUID. */
    public static UUID v5(UUID namespace, String name) {
        byte[] ns = toBytes(namespace);
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[ns.length + n.length];
        System.arraycopy(ns, 0, input, 0, ns.length);
        System.arraycopy(n, 0, input, ns.length, n.length);

        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-1").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }

        byte[] b = new byte[16];
        System.arraycopy(hash, 0, b, 0, 16);
        b[6] = (byte) ((b[6] & 0x0f) | 0x50); // version 5
        b[8] = (byte) ((b[8] & 0x3f) | 0x80); // RFC 4122 variant
        return fromBytes(b);
    }

    private static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    private static UUID fromBytes(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
