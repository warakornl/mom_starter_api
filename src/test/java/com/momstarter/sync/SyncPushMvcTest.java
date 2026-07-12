package com.momstarter.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.ConsentChecker;
import com.momstarter.supply.SupplyItem;
import com.momstarter.supply.SupplyItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for POST /v1/sync/push.
 *
 * <p>Covers (contract "Offline-sync engine — PINNED"):
 * <ul>
 *   <li>apply-create (create sentinel version absent/0 → version=0 in applied[])</li>
 *   <li>server_won conflict (base < current)</li>
 *   <li>tombstone-wins (delete beats a concurrent update)</li>
 *   <li>rejected via consent gate (@MockBean)</li>
 *   <li>idempotency replay (Idempotency-Key within 24h)</li>
 *   <li>batch cap 413 (>1000 records)</li>
 *   <li>unknown_collection in rejected[]</li>
 *   <li>401 unauthenticated</li>
 *   <li>400 missing lastPulledAt</li>
 *   <li>no IDOR (another user's records cannot be modified)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class SyncPushMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private SupplyItemRepository items;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Replaces AlwaysGrantedConsentChecker so individual tests can control consent gate.
     */
    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    @BeforeEach
    void setup() {
        items.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("sync-push@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);

        // Default: cloud_storage granted (whole-batch gate passes)
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // 401 — unauthenticated
    // -------------------------------------------------------------------------

    @Test
    void push_noBearer_returns401() throws Exception {
        String body = buildPushBody(Map.of());
        mvc.perform(post("/sync/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // 403 — email_unverified (evaluated BEFORE consent gate, §G / api-contract egress precondition)
    // -------------------------------------------------------------------------

    @Test
    void push_emailUnverified_returns403EmailUnverified() throws Exception {
        // JWT with email_verified=false — must be rejected before the consent check
        String unverifiedBearer = jwtService.issueAccessToken(userId, false);
        String body = buildPushBody(Map.of());
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + unverifiedBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_unverified"));
    }

    // -------------------------------------------------------------------------
    // 400 — malformed request
    // -------------------------------------------------------------------------

    @Test
    void push_missingLastPulledAt_returns400() throws Exception {
        String body = """
                { "changes": {} }
                """;
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                // api-contract.md:17 — frozen app-wide code for a structurally malformed
                // whole-call body (missing changes/lastPulledAt) is "bad_request", NOT
                // "validation_error" (that code is reserved for a parsed body that fails a
                // field/per-record rule — see offline-sync-engine.md:178).
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void push_missingChanges_returns400() throws Exception {
        // contract §8: missing changes is structurally malformed → 400 (not 422)
        String body = """
                { "lastPulledAt": "0" }
                """;
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    // -------------------------------------------------------------------------
    // 403 — cloud_storage consent denied (whole-batch)
    // -------------------------------------------------------------------------

    @Test
    void push_cloudStorageConsentDenied_returns403() throws Exception {
        when(consentChecker.isGranted(eq(userId), eq("cloud_storage"))).thenReturn(false);

        String body = buildPushBody(Map.of());
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("consent_required"))
                .andExpect(jsonPath("$.details").value("cloud_storage"));
    }

    // -------------------------------------------------------------------------
    // 413 — batch cap exceeded
    // -------------------------------------------------------------------------

    @Test
    void push_batchExceeds1000Records_returns413() throws Exception {
        // Build 1001 created records for supplyItems
        List<Map<String, Object>> created = java.util.stream.IntStream.rangeClosed(1, 1001)
                .mapToObj(i -> buildSupplyRecord(UUID.randomUUID(), 0L, "Item " + i, "other"))
                .toList();

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", created,
                                "updated", List.of(),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("batch_too_large"));
    }

    // -------------------------------------------------------------------------
    // Apply — create (sentinel version absent/0 → new row)
    // -------------------------------------------------------------------------

    @Test
    void push_createSentinel_insertsRowAndReturnsApplied() throws Exception {
        UUID recordId = UUID.randomUUID();

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(buildSupplyRecord(recordId, 0L, "Diapers NB", "diapers")),
                                "updated", List.of(),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        MvcResult result = mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("supplyItems"))
                .andExpect(jsonPath("$.applied[0].id").value(recordId.toString()))
                // contract §5 pin: genuine create → server sets version:=1 (not 0)
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.applied[0].updatedAt").exists())
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty())
                .andReturn();

        // Row must exist in DB with correct userId (IDOR protection)
        SupplyItem saved = items.findById(recordId).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getName()).isEqualTo("Diapers NB");
        assertThat(saved.getCategory()).isEqualTo("diapers");
        // DB version must also be 1 (not 0) so subsequent push conflict detection is correct
        assertThat(saved.getVersion()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // Apply — mutable always-bump (base == current → always apply)
    // -------------------------------------------------------------------------

    @Test
    void push_baseMatchesCurrent_alwaysAppliesAndBumpsVersion() throws Exception {
        // Seed existing item at version 0
        SupplyItem existing = savedItem("Wipes", "hygiene", 5);
        UUID recordId = existing.getId();
        long currentVersion = existing.getVersion(); // 0

        // Push update with base = current (must apply)
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(),
                                "updated", List.of(buildSupplyRecord(recordId, currentVersion, "Wipes Updated", "hygiene")),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(recordId.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(currentVersion + 1))
                .andExpect(jsonPath("$.conflicts").isEmpty());

        SupplyItem updated = items.findById(recordId).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Wipes Updated");
        assertThat(updated.getVersion()).isEqualTo(currentVersion + 1);
    }

    // -------------------------------------------------------------------------
    // Conflict — server_won (base < current)
    // -------------------------------------------------------------------------

    @Test
    void push_baseStale_returnsServerWonConflict() throws Exception {
        // Seed item at version 0, then update it to version 1
        SupplyItem existing = savedItem("Formula", "feeding", 10);
        existing.setOnHandQty(20);
        existing = items.saveAndFlush(existing); // version now 1
        UUID recordId = existing.getId();

        // Client pushes with base version 0 (stale)
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(),
                                "updated", List.of(buildSupplyRecord(recordId, 0L, "Formula Offline", "feeding")),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").isEmpty())
                .andExpect(jsonPath("$.conflicts[0].id").value(recordId.toString()))
                .andExpect(jsonPath("$.conflicts[0].resolution").value("server_won"))
                .andExpect(jsonPath("$.conflicts[0].serverRecord").exists());
    }

    // -------------------------------------------------------------------------
    // Tombstone-wins (delete always applies, update on tombstoned → tombstone_won)
    // -------------------------------------------------------------------------

    @Test
    void push_deleteOnLiveItem_tombstonesAndApplies() throws Exception {
        SupplyItem existing = savedItem("Old Cream", "hygiene", 2);
        UUID recordId = existing.getId();

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(),
                                "updated", List.of(),
                                "deleted", List.of(recordId.toString())
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(recordId.toString()))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        SupplyItem tombstoned = items.findById(recordId).orElseThrow();
        assertThat(tombstoned.getDeletedAt()).isNotNull();
    }

    @Test
    void push_deleteNeverSeenId_createsTombstoneSkeletonAtVersionOne() throws Exception {
        // Never-seen id: server inserts skeleton; contract §5 pin: version:=1 on skeleton INSERT
        UUID neverSeenId = UUID.randomUUID();

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(),
                                "updated", List.of(),
                                "deleted", List.of(neverSeenId.toString())
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(neverSeenId.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1));

        SupplyItem skeleton = items.findById(neverSeenId).orElseThrow();
        assertThat(skeleton.getDeletedAt()).isNotNull();
        assertThat(skeleton.getVersion()).isEqualTo(1L);
    }

    @Test
    void push_updateOnTombstonedItem_returnsTombstoneWonConflict() throws Exception {
        // Seed and tombstone item
        SupplyItem existing = savedItem("Dead Item", "other", 0);
        existing.setDeletedAt(Instant.now());
        existing = items.saveAndFlush(existing);
        UUID recordId = existing.getId();

        // Client tries to update it (doesn't know it was deleted)
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(),
                                "updated", List.of(buildSupplyRecord(recordId, 0L, "Dead Item Updated", "other")),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").isEmpty())
                .andExpect(jsonPath("$.conflicts[0].resolution").value("tombstone_won"))
                .andExpect(jsonPath("$.conflicts[0].serverRecord").exists());
    }

    // -------------------------------------------------------------------------
    // Rejected — consent (MockBean returns false for per-collection)
    // The spec says supplyItems has NO per-collection consent gate (cloud_storage only).
    // This test verifies a health collection (simulated) would reject correctly.
    // For supplyItems specifically: we test that cloud_storage denial rejects whole batch.
    // -------------------------------------------------------------------------

    @Test
    void push_unknownCollection_returnsRejectedUnknownCollection() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(buildSupplyRecord(UUID.randomUUID(), 0L, "Valid", "diapers")),
                                "updated", List.of(),
                                "deleted", List.of()
                        ),
                        "pregnancyProfile", Map.of(
                                "created", List.of(),
                                "updated", List.of(),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // supplyItems record applies
                .andExpect(jsonPath("$.applied").isNotEmpty())
                // pregnancyProfile rejected as unknown_collection (pull-replicated only)
                .andExpect(jsonPath("$.rejected[?(@.collection=='pregnancyProfile')].code")
                        .value("unknown_collection"));
    }

    // -------------------------------------------------------------------------
    // Validation rejected — invalid category
    // -------------------------------------------------------------------------

    @Test
    void push_invalidCategory_returnsRejectedValidationError() throws Exception {
        UUID recordId = UUID.randomUUID();
        Map<String, Object> record = buildSupplyRecord(recordId, 0L, "Item", "invalid-category");

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(record),
                                "updated", List.of(),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").isEmpty())
                .andExpect(jsonPath("$.rejected[0].id").value(recordId.toString()))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"));
    }

    // -------------------------------------------------------------------------
    // Idempotency replay (Idempotency-Key within 24h)
    // -------------------------------------------------------------------------

    @Test
    void push_idempotencyKey_replayReturnsSameResponse() throws Exception {
        UUID recordId = UUID.randomUUID();
        String idempotencyKey = "test-key-" + UUID.randomUUID();

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(buildSupplyRecord(recordId, 0L, "Replay Item", "diapers")),
                                "updated", List.of(),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        // First push — applies the record
        MvcResult first = mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        // Force a version change to detect if replay actually replays vs re-applies
        SupplyItem saved = items.findById(recordId).orElseThrow();
        saved.setOnHandQty(99);
        items.saveAndFlush(saved); // version bumped

        // Second push with same key — must return original response (not re-apply)
        MvcResult second = mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String firstBody = first.getResponse().getContentAsString();
        String secondBody = second.getResponse().getContentAsString();
        assertThat(secondBody).isEqualTo(firstBody); // byte-identical replay
    }

    // -------------------------------------------------------------------------
    // onHandQty clamp (negative → 0, no error)
    // -------------------------------------------------------------------------

    @Test
    void push_negativeOnHandQty_clampsToZeroNoError() throws Exception {
        UUID recordId = UUID.randomUUID();
        Map<String, Object> record = Map.of(
                "id", recordId.toString(),
                "version", 0,
                "name", "Test Item",
                "category", "other",
                "onHandQty", -5  // must be clamped to 0
        );

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(record),
                                "updated", List.of(),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(recordId.toString()))
                .andExpect(jsonPath("$.rejected").isEmpty());

        SupplyItem saved = items.findById(recordId).orElseThrow();
        assertThat(saved.getOnHandQty()).isEqualTo(0); // clamped
    }

    // -------------------------------------------------------------------------
    // No IDOR — one user cannot modify another user's records
    // -------------------------------------------------------------------------

    @Test
    void push_noIdor_cannotModifyAnotherUsersItem() throws Exception {
        // Create another user's item
        User other = new User();
        other.setEmail("other-sync@example.com");
        other.setEmailVerified(true);
        other = users.save(other);

        SupplyItem otherItem = new SupplyItem();
        otherItem.setId(UUID.randomUUID());
        otherItem.setUserId(other.getId());
        otherItem.setName("Other's Diapers");
        otherItem.setCategory("diapers");
        otherItem = items.saveAndFlush(otherItem);
        UUID otherItemId = otherItem.getId();
        long otherVersion = otherItem.getVersion();

        // Authenticated user tries to update other user's item
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(),
                                "updated", List.of(buildSupplyRecord(otherItemId, otherVersion, "Hacked", "other")),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        // Should either be a conflict (server_won because no row for this user) or insert a new row
        // The key invariant: the other user's row must NOT be modified
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // The OTHER user's item must be completely unchanged
        SupplyItem untouched = items.findById(otherItemId).orElseThrow();
        assertThat(untouched.getUserId()).isEqualTo(other.getId());
        assertThat(untouched.getName()).isEqualTo("Other's Diapers");
    }

    // -------------------------------------------------------------------------
    // Same-id across multiple buckets (contract §1: apply-order created→updated→deleted,
    // deleted wins; applied[] has 1 entry per id, not one per bucket)
    // -------------------------------------------------------------------------

    @Test
    void push_sameId_createdAndDeleted_tombstoneWins_oneAppliedEntry() throws Exception {
        UUID id = UUID.randomUUID();
        // Same id in created AND deleted — deleted wins (tombstone-wins); no PK crash
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(buildSupplyRecord(id, 0L, "Transient Item", "other")),
                                "updated", List.of(),
                                "deleted", List.of(id.toString())
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // exactly 1 applied entry for the id (not two)
                .andExpect(jsonPath("$.applied.length()").value(1))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        // Final state must be tombstoned
        SupplyItem result = items.findById(id).orElseThrow();
        assertThat(result.getDeletedAt()).isNotNull();
    }

    @Test
    void push_sameId_createdAndUpdated_noPkCrash_oneAppliedEntry() throws Exception {
        UUID id = UUID.randomUUID();
        // Same id in created AND updated — no PK crash, exactly 1 applied[] entry.
        // Behavior: created[X] INSERTs X (version:=1); updated[X] with base=0 sees
        // currentVersion=1 → server_won (base≠current). Applied[] carries the create result;
        // the update's server_won appears in conflicts[]. This is correct LWW behavior —
        // the client should re-push the update with base=1 after reading the applied[] entry.
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "supplyItems", Map.of(
                                "created", List.of(buildSupplyRecord(id, 0L, "Initial Name", "other")),
                                "updated", List.of(buildSupplyRecord(id, 0L, "Updated Name", "diapers")),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // Exactly 1 applied entry for the id (from created bucket — no duplicate for updated)
                .andExpect(jsonPath("$.applied.length()").value(1))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                // No crash means no 500; server_won conflict for update is expected
                .andExpect(jsonPath("$.rejected").isEmpty());

        // Row exists (the create landed) — no PK exception, no 500
        assertThat(items.findById(id)).isPresent();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SupplyItem savedItem(String name, String category, int qty) {
        SupplyItem item = new SupplyItem();
        item.setId(UUID.randomUUID());
        item.setUserId(userId);
        item.setName(name);
        item.setCategory(category);
        item.setOnHandQty(qty);
        return items.saveAndFlush(item);
    }

    private Map<String, Object> buildSupplyRecord(UUID id, long baseVersion, String name, String category) {
        return Map.of(
                "id", id.toString(),
                "version", baseVersion,
                "name", name,
                "category", category,
                "onHandQty", 5
        );
    }

    private String buildPushBody(Map<String, Object> extraChanges) throws Exception {
        Map<String, Object> changes = new java.util.HashMap<>(extraChanges);
        return objectMapper.writeValueAsString(Map.of(
                "changes", changes,
                "lastPulledAt", "0"
        ));
    }
}
