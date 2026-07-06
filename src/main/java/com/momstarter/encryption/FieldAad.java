package com.momstarter.encryption;

import java.nio.charset.StandardCharsets;

/**
 * AAD (Additional Authenticated Data) for a single encrypted field.
 *
 * <p>Encodes the field's ownership context into a fixed UTF-8 string that is
 * bound into every GCM tag — so moving or copying a ciphertext to a different
 * account, collection, record, or field causes tag verification to fail.
 *
 * <h2>Wire format (LOCKED — appsec RULING 2)</h2>
 * <pre>
 *   "v1:" + accountId + ":" + collection + ":" + recordId + ":" + fieldName
 * </pre>
 *
 * <h2>Stability contract (AAD-name-stability — appsec RULING 2a)</h2>
 * <p>{@code collection} and {@code fieldName} are <em>frozen logical identifiers</em>
 * that are baked into every existing GCM tag. They must never be changed after the
 * first write, even if the wire/JSON field name is later renamed. Changing either
 * without bumping the envelope version and re-encrypting every row causes every
 * existing tag to fail ("silent health-data loss").
 *
 * <h2>version/AAD prefix lock-step (appsec RULING 2a)</h2>
 * <p>The {@code "v1:"} prefix moves in lock-step with the envelope version byte
 * {@code 0x01} ({@link FieldEnvelope#VERSION_BYTE}). A future v2 envelope
 * must use {@code "v2:"} with version byte {@code 0x02}.
 *
 * <h2>pregnancyProfile / row-per-account tables (appsec RULING 2b)</h2>
 * <p>For tables with one row per account (e.g., {@code pregnancyProfile}), use
 * {@code recordId = accountId}. The resulting AAD is:
 * <pre>
 *   "v1:<accountId>:pregnancyProfile:<accountId>:<fieldName>"
 * </pre>
 *
 * <h2>Delimiter injection prevention</h2>
 * <p>{@code accountId} and {@code recordId} are canonical lowercase UUID strings
 * (hex + '-' only, no ':'). {@code collection} and {@code fieldName} are
 * application-controlled identifiers and must never contain ':'.
 *
 * @param accountId  canonical lowercase 36-character UUID (the account owner)
 * @param collection frozen logical collection name (e.g., {@code "expenses"}),
 *                   never the wire/JSON name — must not contain ':'
 * @param recordId   client-generated UUID for the record;
 *                   for row-per-account tables use {@code accountId}
 * @param fieldName  frozen logical field name (e.g., {@code "note"}),
 *                   never the wire/JSON name — must not contain ':'
 */
public record FieldAad(String accountId, String collection, String recordId, String fieldName) {

    /** AAD version prefix — lock-stepped with envelope version byte 0x01. */
    private static final String VERSION_PREFIX = "v1:";

    /**
     * Encodes this AAD as UTF-8 bytes for passing to the JCE cipher via
     * {@code Cipher.updateAAD(byte[])}.
     *
     * <p>The resulting string is:
     * <pre>
     *   "v1:" + accountId + ":" + collection + ":" + recordId + ":" + fieldName
     * </pre>
     *
     * @return UTF-8 encoding of the AAD string — never null or empty
     */
    public byte[] toAadBytes() {
        String aadString = VERSION_PREFIX + accountId + ':' + collection + ':' + recordId + ':' + fieldName;
        return aadString.getBytes(StandardCharsets.UTF_8);
    }
}
