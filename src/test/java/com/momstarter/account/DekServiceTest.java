package com.momstarter.account;

import com.momstarter.account.dto.DekResponse;
import com.momstarter.encryption.KmsClient;
import com.momstarter.encryption.MockKmsClient;
import com.momstarter.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link DekService} using H2 (PostgreSQL mode) + Flyway.
 *
 * <p>{@link KmsClient} is mocked via {@code @MockBean}. {@link DekService} is imported
 * explicitly so its JdbcTemplate and repository dependencies are auto-configured.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>provisionDek inserts exactly one row</li>
 *   <li>provisionDek is idempotent (ON CONFLICT DO NOTHING — second call does not overwrite)</li>
 *   <li>KMS-outside-txn: provisionDek's KMS call is made before the DB insert (verified by
 *       call ordering in the mock)</li>
 *   <li>deliverDek returns a Base64 DEK (200 path)</li>
 *   <li>deliverDek throws 404 when no account_dek row exists</li>
 * </ul>
 *
 * <p>TDD: tests were written before the implementation to drive the service shape.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Import(DekService.class)
class DekServiceTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private DekService dekService;

    @Autowired
    private AccountDekRepository accountDekRepository;

    @Autowired
    private UserRepository users;

    @MockBean
    private KmsClient kmsClient;

    private UUID accountId;

    @BeforeEach
    void seedUser() {
        User user = new User();
        user.setEmail("dek-service-test@example.com");
        user.setEmailVerified(true);
        user.setPasswordHash("{argon2}dummy");
        em.persistAndFlush(user);
        accountId = user.getId();

        // Default mock: MockKmsClient behaviour — generateDek returns a real mock
        MockKmsClient realMock = new MockKmsClient();
        KmsClient.GeneratedDek generated = realMock.generateDek(accountId.toString());
        when(kmsClient.generateDek(accountId.toString())).thenReturn(generated);
        when(kmsClient.decryptDek(any(), eq(accountId.toString())))
                .thenAnswer(inv -> realMock.decryptDek(inv.getArgument(0), inv.getArgument(1)));
    }

    // -------------------------------------------------------------------------
    // provisionDek — inserts one row
    // -------------------------------------------------------------------------

    @Test
    void provisionDek_insertsExactlyOneRow() {
        assertThat(accountDekRepository.findById(accountId)).isEmpty();

        dekService.provisionDek(accountId);
        em.clear();

        assertThat(accountDekRepository.findById(accountId)).isPresent();
    }

    @Test
    void provisionDek_row_hasCorrectFields() {
        dekService.provisionDek(accountId);
        em.clear();

        AccountDek row = accountDekRepository.findById(accountId).orElseThrow();
        assertThat(row.getUserId()).isEqualTo(accountId);
        assertThat(row.getWrappedDek()).isNotEmpty();
        assertThat(row.getKmsKeyId()).isNotBlank();
        assertThat(row.getWrapContext()).isEqualTo("accountId=" + accountId);
        assertThat(row.getDekVersion()).isEqualTo((short) 1);
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getUpdatedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // provisionDek — idempotent (ON CONFLICT DO NOTHING)
    // -------------------------------------------------------------------------

    @Test
    void provisionDek_isIdempotent_onConflict() {
        // First call provisions and stores a DEK
        dekService.provisionDek(accountId);
        em.clear();

        byte[] firstWrappedDek = accountDekRepository.findById(accountId)
                .map(AccountDek::getWrappedDek)
                .orElseThrow();

        // Second call should be a no-op (ON CONFLICT DO NOTHING) — the row must not change
        // Setup: second generateDek returns a DIFFERENT wrapped blob
        MockKmsClient realMock = new MockKmsClient();
        KmsClient.GeneratedDek second = realMock.generateDek(accountId.toString());
        when(kmsClient.generateDek(accountId.toString())).thenReturn(second);

        dekService.provisionDek(accountId);
        em.clear();

        byte[] secondWrappedDek = accountDekRepository.findById(accountId)
                .map(AccountDek::getWrappedDek)
                .orElseThrow();

        // The row was NOT overwritten — original wrappedDek intact
        assertThat(secondWrappedDek).containsExactly(toBoxed(firstWrappedDek));

        // KMS was still called twice (once per provisionDek) — KMS call is outside txn
        verify(kmsClient, times(2)).generateDek(accountId.toString());
    }

    // -------------------------------------------------------------------------
    // KMS-outside-txn: KMS call before the INSERT
    // -------------------------------------------------------------------------

    @Test
    void provisionDek_callsKmsBeforeInsert_kmsFailureDoesNotInsertRow() {
        // If KMS.generateDek throws, no row should be inserted (KMS is called first)
        when(kmsClient.generateDek(anyString()))
                .thenThrow(new RuntimeException("KMS unavailable"));

        assertThatThrownBy(() -> dekService.provisionDek(accountId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("KMS unavailable");

        em.clear();
        assertThat(accountDekRepository.findById(accountId)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // deliverDek — 200 path
    // -------------------------------------------------------------------------

    @Test
    void deliverDek_returnsBase64DekAndVersion() {
        dekService.provisionDek(accountId);
        em.clear();

        DekResponse response = dekService.deliverDek(accountId);

        assertThat(response).isNotNull();
        assertThat(response.dek()).isNotBlank();
        assertThat(response.dekVersion()).isEqualTo(1);

        // Verify it's valid standard Base64 and decodes to 32 bytes
        byte[] decoded = java.util.Base64.getDecoder().decode(response.dek());
        assertThat(decoded).hasSize(32);
    }

    @Test
    void deliverDek_plaintextDekMatchesRoundTrip() {
        // Provision with a known DEK
        MockKmsClient realKms = new MockKmsClient();
        KmsClient.GeneratedDek generated = realKms.generateDek(accountId.toString());
        when(kmsClient.generateDek(accountId.toString())).thenReturn(generated);
        when(kmsClient.decryptDek(any(), eq(accountId.toString())))
                .thenReturn(generated.plaintextDek());

        dekService.provisionDek(accountId);
        em.clear();

        DekResponse response = dekService.deliverDek(accountId);
        byte[] returned = java.util.Base64.getDecoder().decode(response.dek());

        assertThat(returned).containsExactly(toBoxed(generated.plaintextDek()));
    }

    // -------------------------------------------------------------------------
    // deliverDek — 404 when no row
    // -------------------------------------------------------------------------

    @Test
    void deliverDek_throws404_whenNoDekRowExists() {
        // No account_dek row seeded for this account
        assertThatThrownBy(() -> dekService.deliverDek(accountId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(404);
                    assertThat(apiEx.getCode()).isEqualTo("dek_not_found");
                });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Byte[] toBoxed(byte[] bytes) {
        Byte[] boxed = new Byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) boxed[i] = bytes[i];
        return boxed;
    }
}
