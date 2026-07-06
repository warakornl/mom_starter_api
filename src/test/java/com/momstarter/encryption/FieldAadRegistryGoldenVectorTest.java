package com.momstarter.encryption;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.AccountExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden-vector tests for ALL 8 frozen AAD registry tuples defined in
 * {@code docs/security/field-aad-registry.md} (appsec gap G4 — closed by this class).
 *
 * <p>Before this class, {@code golden-vectors.json} pinned only the demo
 * {@code expenses/note} tuple. Real-tuple coverage was 0/8. This class closes
 * that gap by:
 * <ol>
 *   <li><b>Golden match</b> — AES-256-GCM with a fixed IV must produce byte-identical
 *       output matching the committed {@code expected_envelope_hex}. This is the
 *       cross-impl contract: mobile (react-native-quick-crypto) and Node MUST reproduce
 *       these bytes exactly for the same (DEK, IV, plaintext, AAD).</li>
 *   <li><b>Round-trip</b> — {@link FieldEnvelopeDecryptor} decrypts the committed
 *       envelope back to the original UTF-8 plaintext (proves the production decode path).</li>
 *   <li><b>AAD binding / tamper negative</b> — decrypting the committed envelope with a
 *       mutated AAD (wrong accountId) throws, proving GCM tags are bound to ownership context.
 *       This is the exact divergence class that gap G4 warns about: a ciphertext copied to
 *       the wrong record/account would fail to decrypt rather than silently return wrong data.</li>
 *   <li><b>Registry-drift guard</b> — each tuple's {@code collection} and {@code fieldName}
 *       tokens are verified via reflection against the private frozen constants in
 *       {@link AccountExportService}. If a constant is renamed or its value is changed,
 *       this test fails BEFORE any ciphertext is written to the database — giving immediate
 *       notice that the change would silently destroy all existing encrypted health data
 *       (tag rejection = data loss with no decryption error at read time).</li>
 * </ol>
 *
 * <h2>How the drift guard works (requirement d)</h2>
 * <p>The private static constants in {@link AccountExportService} (e.g. {@code COLL_SELF_LOG},
 * {@code FIELD_SELF_LOG_VALUE_NUMERIC}) are accessed via reflection. The guard maps each
 * (collection, fieldName) registry tuple to the corresponding constant field name, then:
 * <ul>
 *   <li>If the constant's Java field name is renamed (e.g. {@code COLL_SELF_LOG} →
 *       {@code COLL_SELF_LOGS}), {@code getDeclaredField} throws {@code NoSuchFieldException}
 *       → test fails immediately.</li>
 *   <li>If the constant's value is changed (e.g. {@code "selfLog"} → {@code "selflog"}),
 *       the {@code assertThat(value).isEqualTo(token)} assertion fails.</li>
 * </ul>
 * <p>Both scenarios cause a test failure before any data is written — protecting against
 * silent health-data loss (appsec RULING 2 / name-stability invariant).
 *
 * <h2>Test data</h2>
 * <ul>
 *   <li>Shared test DEK: 32 zero bytes (AES-256, test-only).</li>
 *   <li>Shared accountId: {@code cccccccc-cccc-cccc-cccc-cccccccccccc}.</li>
 *   <li>Shared recordId: {@code dddddddd-dddd-dddd-dddd-dddddddddddd} (client-generated UUID).</li>
 *   <li>Per-vector IVs: {@code 000000000000000000000001} through {@code 000000000000000000000008}
 *       (last byte = 1..8) — demonstrates IV independence; each vector has a distinct nonce.</li>
 *   <li>Plaintexts: realistic values including Thai UTF-8 multibyte strings to prove
 *       UTF-8 encoding correctness end-to-end.</li>
 * </ul>
 *
 * <h2>Committed bytes as cross-platform contract</h2>
 * <p>The {@code expected_envelope_hex} values in {@code golden-vectors.json} are the
 * single source of truth. Once committed, mobile and Node fixture tests MUST match them
 * byte-for-byte. Changing these values (or the plaintext / IV / DEK / AAD that produced
 * them) is a breaking change requiring a version bump to {@code 0x02}.
 *
 * @see FieldEnvelopeTest#GoldenVectors for the original demo golden vector
 * @see AccountExportService for the frozen AAD constant definitions
 */
@DisplayName("Registry golden vectors — all 8 frozen AAD tuples (gap G4)")
class FieldAadRegistryGoldenVectorTest {

    // -----------------------------------------------------------------------
    // Drift-guard constant name maps (LOCKED — mirrors AccountExportService private constants)
    //
    // Key: value that the constant is expected to hold (= the frozen AAD token from registry).
    // If a constant's JAVA FIELD NAME changes, getDeclaredField throws NoSuchFieldException
    //   → test fails immediately.
    // If a constant's VALUE changes, assertThat(value).isEqualTo(token) fails.
    // Both scenarios prevent silent health-data loss.
    // -----------------------------------------------------------------------

    /**
     * Maps frozen collection token → AccountExportService private static field name.
     *
     * <p>Constant mapping:
     * <ul>
     *   <li>{@code "selfLog"}          → {@code AccountExportService.COLL_SELF_LOG}</li>
     *   <li>{@code "medicationPlan"}   → {@code AccountExportService.COLL_MEDICATION_PLAN}</li>
     *   <li>{@code "medicationLog"}    → {@code AccountExportService.COLL_MEDICATION_LOG}</li>
     *   <li>{@code "kickCountSession"} → {@code AccountExportService.COLL_KICK_COUNT_SESSION}</li>
     * </ul>
     */
    private static final Map<String, String> COLL_CONST_NAMES = Map.of(
            "selfLog",          "COLL_SELF_LOG",
            "medicationPlan",   "COLL_MEDICATION_PLAN",
            "medicationLog",    "COLL_MEDICATION_LOG",
            "kickCountSession", "COLL_KICK_COUNT_SESSION"
    );

    /**
     * Maps frozen {@code "collection:fieldName"} key → AccountExportService private static field name.
     *
     * <p>Constant mapping:
     * <ul>
     *   <li>{@code "selfLog:valueNumeric"}          → {@code AccountExportService.FIELD_SELF_LOG_VALUE_NUMERIC}</li>
     *   <li>{@code "selfLog:valueNumericSecondary"} → {@code AccountExportService.FIELD_SELF_LOG_VALUE_NUMERIC_SECONDARY}</li>
     *   <li>{@code "selfLog:valueText"}             → {@code AccountExportService.FIELD_SELF_LOG_VALUE_TEXT}</li>
     *   <li>{@code "selfLog:note"}                  → {@code AccountExportService.FIELD_SELF_LOG_NOTE}</li>
     *   <li>{@code "medicationPlan:name"}           → {@code AccountExportService.FIELD_MED_PLAN_NAME}</li>
     *   <li>{@code "medicationPlan:dose"}           → {@code AccountExportService.FIELD_MED_PLAN_DOSE}</li>
     *   <li>{@code "medicationLog:note"}            → {@code AccountExportService.FIELD_MED_LOG_NOTE}</li>
     *   <li>{@code "kickCountSession:note"}         → {@code AccountExportService.FIELD_KICK_NOTE}</li>
     * </ul>
     */
    private static final Map<String, String> FIELD_CONST_NAMES = Map.of(
            "selfLog:valueNumeric",          "FIELD_SELF_LOG_VALUE_NUMERIC",
            "selfLog:valueNumericSecondary", "FIELD_SELF_LOG_VALUE_NUMERIC_SECONDARY",
            "selfLog:valueText",             "FIELD_SELF_LOG_VALUE_TEXT",
            "selfLog:note",                  "FIELD_SELF_LOG_NOTE",
            "medicationPlan:name",           "FIELD_MED_PLAN_NAME",
            "medicationPlan:dose",           "FIELD_MED_PLAN_DOSE",
            "medicationLog:note",            "FIELD_MED_LOG_NOTE",
            "kickCountSession:note",         "FIELD_KICK_NOTE"
    );

    // -----------------------------------------------------------------------
    // Registry vector data record
    // -----------------------------------------------------------------------

    /**
     * Holds all fields needed to drive the 4 test assertions for one registry tuple.
     * Loaded from the {@code registry_vectors.vectors} array in {@code golden-vectors.json}.
     */
    record RegistryVector(
            int tupleNum,
            String id,
            String collection,
            String fieldName,
            String accountId,
            String recordId,
            String plaintextUtf8,
            byte[] dek,
            byte[] iv,
            String expectedEnvelopeHex
    ) {
        @Override
        public String toString() {
            return "tuple-" + tupleNum + ": " + collection + "/" + fieldName + " [" + id + "]";
        }
    }

    // -----------------------------------------------------------------------
    // @MethodSource — loads all 8 registry vectors from golden-vectors.json
    // -----------------------------------------------------------------------

    /**
     * Provides all 8 registry vectors from the {@code registry_vectors.vectors} array
     * in {@code src/test/resources/encryption/golden-vectors.json}.
     *
     * <p>Each entry is loaded once and reused across all 4 {@link ParameterizedTest} methods.
     * The JSON is the single source of truth: updating {@code expected_envelope_hex} in JSON
     * is the only change needed to fix a golden-vector mismatch.
     */
    static Stream<RegistryVector> registryVectors() {
        try (InputStream is = FieldAadRegistryGoldenVectorTest.class
                .getResourceAsStream("/encryption/golden-vectors.json")) {
            assertThat(is)
                    .as("golden-vectors.json must be present on the test classpath")
                    .isNotNull();

            JsonNode root = new ObjectMapper().readTree(is);
            JsonNode registryNode = root.path("registry_vectors").path("vectors");
            assertThat(registryNode.isMissingNode())
                    .as("golden-vectors.json must contain a 'registry_vectors.vectors' array")
                    .isFalse();
            assertThat(registryNode.isArray())
                    .as("registry_vectors.vectors must be a JSON array")
                    .isTrue();

            HexFormat hex = HexFormat.of();
            List<RegistryVector> vectors = new ArrayList<>();
            int tupleNum = 1;
            for (JsonNode v : registryNode) {
                byte[] dek = hex.parseHex(v.path("dek_hex").asText());
                byte[] iv  = hex.parseHex(v.path("iv_hex").asText());
                vectors.add(new RegistryVector(
                        tupleNum++,
                        v.path("id").asText(),
                        v.path("collection").asText(),
                        v.path("fieldName").asText(),
                        v.path("accountId").asText(),
                        v.path("recordId").asText(),
                        v.path("plaintext_utf8").asText(),
                        dek,
                        iv,
                        v.path("expected_envelope_hex").asText()
                ));
            }
            assertThat(vectors)
                    .as("registry_vectors.vectors must contain exactly 8 entries (one per registered AAD tuple)")
                    .hasSize(8);
            return vectors.stream();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load registry vectors from golden-vectors.json", e);
        }
    }

    // -----------------------------------------------------------------------
    // (a) Golden match — byte-identical contract for cross-impl parity
    // -----------------------------------------------------------------------

    /**
     * Encrypts the vector's plaintext with its fixed DEK + IV + FieldAad and asserts the
     * resulting envelope hex equals the committed {@code expected_envelope_hex}.
     *
     * <p>This is the cross-impl byte-identical contract (appsec RULING 8 / I-2).
     * Mobile (react-native-quick-crypto / BoringSSL) and Node (Node crypto module) MUST
     * produce the same hex for the same (DEK, IV, plaintext, AAD) combination.
     *
     * <p>Uses {@link FieldEnvelope#encryptWithIv} (package-private, test-seam only) to inject
     * a fixed IV. Production code always calls {@link FieldEnvelope#encrypt} which generates
     * a fresh CSPRNG IV — this test method is the only place a fixed IV is used.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("registryVectors")
    @DisplayName("(a) golden match — fixed IV produces byte-identical envelope (cross-impl contract)")
    void goldenMatch_fixedIvProducesByteIdenticalEnvelope(RegistryVector v) {
        byte[] plaintext = v.plaintextUtf8().getBytes(StandardCharsets.UTF_8);
        FieldAad aad = new FieldAad(v.accountId(), v.collection(), v.recordId(), v.fieldName());

        // Package-private test seam: injects fixed IV for deterministic output.
        // Production code MUST use FieldEnvelope.encrypt (fresh CSPRNG IV).
        byte[] envelope = FieldEnvelope.encryptWithIv(plaintext, v.dek(), aad, v.iv());

        String actualHex = HexFormat.of().formatHex(envelope);

        // Assert: JVM output must match the committed golden-vectors.json value.
        // If this fails, the actual hex in the failure message IS the value to commit.
        assertThat(actualHex)
                .as("JVM AES-256-GCM output for %s must match expected_envelope_hex in golden-vectors.json — "
                    + "if this is a first-run, copy the 'but was' value into the JSON", v)
                .isEqualTo(v.expectedEnvelopeHex());
    }

    // -----------------------------------------------------------------------
    // (b) Round-trip — FieldEnvelopeDecryptor recovers original UTF-8 plaintext
    // -----------------------------------------------------------------------

    /**
     * Encrypts with a fixed IV and then decrypts via the production
     * {@link FieldEnvelopeDecryptor#decryptFromBase64} path, asserting the recovered
     * string equals the original UTF-8 plaintext.
     *
     * <p>This test exercises the full read path that {@code AccountExportService} uses.
     * It also proves UTF-8 multibyte handling (Thai strings in tuples 3, 4, 7, 8).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("registryVectors")
    @DisplayName("(b) round-trip — FieldEnvelopeDecryptor decrypts back to original UTF-8 plaintext")
    void roundTrip_decryptorRecoversPlaintext(RegistryVector v) {
        byte[] plaintext = v.plaintextUtf8().getBytes(StandardCharsets.UTF_8);
        FieldAad aad = new FieldAad(v.accountId(), v.collection(), v.recordId(), v.fieldName());

        byte[] envelope = FieldEnvelope.encryptWithIv(plaintext, v.dek(), aad, v.iv());
        String wireBase64 = Base64.getEncoder().encodeToString(envelope);

        // Use the production FieldEnvelopeDecryptor path (same as AccountExportService.dispatchDecrypt)
        FieldEnvelopeDecryptor decryptor = new FieldEnvelopeDecryptor(new LegacyCutoverPolicy());
        String recovered = decryptor.decryptFromBase64(wireBase64, v.dek(), aad);

        assertThat(recovered)
                .as("round-trip for %s: decrypted string must equal original UTF-8 plaintext", v)
                .isEqualTo(v.plaintextUtf8());

        // Additional UTF-8 byte-level check: the recovered string must re-encode to the same bytes.
        assertThat(recovered.getBytes(StandardCharsets.UTF_8))
                .as("round-trip for %s: re-encoded UTF-8 bytes must equal original plaintext bytes", v)
                .isEqualTo(plaintext);
    }

    // -----------------------------------------------------------------------
    // (c) AAD binding / tamper negative — wrong accountId causes tag failure
    // -----------------------------------------------------------------------

    /**
     * Encrypts with the correct AAD, then attempts to decrypt with a mutated accountId.
     * GCM must reject the envelope (tag verification failure).
     *
     * <p>This is the primary divergence class that gap G4 warns about: if a ciphertext were
     * moved to a different account, the decryption would fail with {@link SecurityException}
     * rather than silently returning the wrong user's data. Verifies appsec RULING 2
     * (AAD binding).
     *
     * <p>The mutated accountId is {@code eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee} — one
     * character flip from the correct {@code cccccccc-cccc-cccc-cccc-cccccccccccc}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("registryVectors")
    @DisplayName("(c) tamper negative — wrong accountId in AAD causes GCM tag failure (RULING 2)")
    void tamperNegative_wrongAccountIdCausesTagFailure(RegistryVector v) {
        byte[] plaintext = v.plaintextUtf8().getBytes(StandardCharsets.UTF_8);
        FieldAad correctAad = new FieldAad(v.accountId(), v.collection(), v.recordId(), v.fieldName());
        byte[] envelope = FieldEnvelope.encryptWithIv(plaintext, v.dek(), correctAad, v.iv());

        // Mutated AAD: different accountId — simulates cross-account ciphertext copy
        FieldAad mutatedAad = new FieldAad(
                "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", // wrong accountId
                v.collection(),
                v.recordId(),
                v.fieldName()
        );

        assertThatThrownBy(() -> FieldEnvelope.decrypt(envelope, v.dek(), mutatedAad))
                .as("decrypting %s with wrong accountId must throw SecurityException (GCM tag binding)", v)
                .isInstanceOf(SecurityException.class);
    }

    // -----------------------------------------------------------------------
    // (d) Registry-drift guard — constants in AccountExportService match JSON tokens
    // -----------------------------------------------------------------------

    /**
     * Verifies that each tuple's frozen {@code collection} and {@code fieldName} tokens
     * match the corresponding private static constants in {@link AccountExportService}.
     *
     * <h2>Why this test fails on rename</h2>
     * <p>The constant field names (e.g. {@code "COLL_SELF_LOG"}) are stored in
     * {@link #COLL_CONST_NAMES} and {@link #FIELD_CONST_NAMES} maps in this test.
     * If a constant's Java field name changes in {@code AccountExportService}:
     * <ul>
     *   <li>{@code getDeclaredField("COLL_SELF_LOG")} throws {@link NoSuchFieldException}
     *       → test fails immediately, stopping CI before any write to the DB.</li>
     * </ul>
     * If a constant's string value changes (e.g. {@code "selfLog"} → {@code "selflog"}):
     * <ul>
     *   <li>{@code assertThat("selflog").isEqualTo("selfLog")} fails with a clear message.</li>
     * </ul>
     *
     * <h2>Constant-to-tuple mapping (hardcoded in this test)</h2>
     * <ul>
     *   <li>selfLog / valueNumeric          → COLL_SELF_LOG + FIELD_SELF_LOG_VALUE_NUMERIC</li>
     *   <li>selfLog / valueNumericSecondary → COLL_SELF_LOG + FIELD_SELF_LOG_VALUE_NUMERIC_SECONDARY</li>
     *   <li>selfLog / valueText             → COLL_SELF_LOG + FIELD_SELF_LOG_VALUE_TEXT</li>
     *   <li>selfLog / note                  → COLL_SELF_LOG + FIELD_SELF_LOG_NOTE</li>
     *   <li>medicationPlan / name           → COLL_MEDICATION_PLAN + FIELD_MED_PLAN_NAME</li>
     *   <li>medicationPlan / dose           → COLL_MEDICATION_PLAN + FIELD_MED_PLAN_DOSE</li>
     *   <li>medicationLog / note            → COLL_MEDICATION_LOG + FIELD_MED_LOG_NOTE</li>
     *   <li>kickCountSession / note         → COLL_KICK_COUNT_SESSION + FIELD_KICK_NOTE</li>
     * </ul>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("registryVectors")
    @DisplayName("(d) registry-drift guard — AccountExportService frozen constants match JSON tokens")
    void registryDriftGuard_constantsMatchAccountExportService(RegistryVector v) throws Exception {
        Class<?> svc = AccountExportService.class;

        // --- Verify collection constant ---
        String collConstName = COLL_CONST_NAMES.get(v.collection());
        assertThat(collConstName)
                .as("No COLL_CONST_NAMES mapping for collection='%s' — registry JSON may have drifted; "
                    + "add a mapping entry to keep this guard working", v.collection())
                .isNotNull();

        Field collField = svc.getDeclaredField(collConstName);
        // getDeclaredField throws NoSuchFieldException if the constant was renamed in AccountExportService
        collField.setAccessible(true);
        String collValue = (String) collField.get(null);
        assertThat(collValue)
                .as("AccountExportService.%s must equal frozen collection token '%s' — "
                    + "changing this value would silently destroy all existing GCM tags for this collection",
                    collConstName, v.collection())
                .isEqualTo(v.collection());

        // --- Verify fieldName constant ---
        String compositeKey = v.collection() + ":" + v.fieldName();
        String fieldConstName = FIELD_CONST_NAMES.get(compositeKey);
        assertThat(fieldConstName)
                .as("No FIELD_CONST_NAMES mapping for key='%s' — registry JSON may have drifted; "
                    + "add a mapping entry to keep this guard working", compositeKey)
                .isNotNull();

        Field fieldConst = svc.getDeclaredField(fieldConstName);
        // getDeclaredField throws NoSuchFieldException if the constant was renamed
        fieldConst.setAccessible(true);
        String fieldValue = (String) fieldConst.get(null);
        assertThat(fieldValue)
                .as("AccountExportService.%s must equal frozen fieldName token '%s' — "
                    + "changing this value would silently destroy all existing GCM tags for this field",
                    fieldConstName, v.fieldName())
                .isEqualTo(v.fieldName());

        // --- Verify the produced AAD bytes match an independently-built FieldAad ---
        // This catches any drift in the FieldAad wire format itself.
        FieldAad fromRegistry = new FieldAad(v.accountId(), v.collection(), v.recordId(), v.fieldName());
        FieldAad fromConstants = new FieldAad(v.accountId(), collValue, v.recordId(), fieldValue);
        assertThat(fromConstants.toAadBytes())
                .as("FieldAad built from AccountExportService constants must produce identical AAD bytes "
                    + "as FieldAad built from registry tokens for %s", v)
                .isEqualTo(fromRegistry.toAadBytes());
    }
}
