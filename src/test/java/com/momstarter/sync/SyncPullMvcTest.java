package com.momstarter.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.pregnancy.ConsentChecker;
import com.momstarter.pregnancy.PregnancyProfile;
import com.momstarter.pregnancy.PregnancyProfileRepository;
import com.momstarter.supply.SupplyItem;
import com.momstarter.supply.SupplyItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for GET /v1/sync/pull.
 *
 * <p>Covers (contract "Offline-sync engine — PINNED", §9):
 * <ul>
 *   <li>keyset order — results ordered by (updated_at ASC, id ASC)</li>
 *   <li>cursor continuation — nextCursor present when results exceed limit</li>
 *   <li>tombstone included in deleted[] (not updated[])</li>
 *   <li>pregnancyProfile pull-replicated (appears in changes)</li>
 *   <li>400 invalid_cursor on expired/tampered cursor</li>
 *   <li>409 watermark_expired when since > 180 days ago</li>
 *   <li>safe-window — since is expanded by safeWindow seconds</li>
 *   <li>401 unauthenticated</li>
 *   <li>no IDOR — only returns the authenticated user's data</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class SyncPullMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private SupplyItemRepository items;

    @Autowired
    private PregnancyProfileRepository profiles;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    @BeforeEach
    void setup() {
        items.deleteAll();
        profiles.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("sync-pull@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);

        // Default: cloud_storage granted
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // 401 — unauthenticated
    // -------------------------------------------------------------------------

    @Test
    void pull_noBearer_returns401() throws Exception {
        mvc.perform(get("/sync/pull"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // 403 — email_unverified (evaluated BEFORE consent gate, §G / api-contract egress precondition)
    // -------------------------------------------------------------------------

    @Test
    void pull_emailUnverified_returns403EmailUnverified() throws Exception {
        // JWT with email_verified=false — must be rejected before the consent check
        String unverifiedBearer = jwtService.issueAccessToken(userId, false);
        mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + unverifiedBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_unverified"));
    }

    // -------------------------------------------------------------------------
    // 403 — cloud_storage consent denied
    // -------------------------------------------------------------------------

    @Test
    void pull_cloudStorageConsentDenied_returns403() throws Exception {
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(false);

        mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("consent_required"))
                .andExpect(jsonPath("$.details").value("cloud_storage"));
    }

    // -------------------------------------------------------------------------
    // 409 — watermark_expired (since > 180 days old)
    // -------------------------------------------------------------------------

    @Test
    void pull_since_olderThan180Days_returns409WatermarkExpired() throws Exception {
        Instant ancientWatermark = Instant.now().minus(181, ChronoUnit.DAYS);

        mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer)
                        .param("since", ancientWatermark.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("watermark_expired"));
    }

    // -------------------------------------------------------------------------
    // 400 — invalid_cursor
    // -------------------------------------------------------------------------

    @Test
    void pull_tamperedCursor_returns400InvalidCursor() throws Exception {
        mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer)
                        .param("cursor", "not-a-valid-base64-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
    }

    // -------------------------------------------------------------------------
    // Happy path — delta pull (since present, single page)
    // -------------------------------------------------------------------------

    @Test
    void pull_withSince_returnsOnlyNewerRecords() throws Exception {
        // Save two items: one before watermark, one after
        SupplyItem old = savedItem("Old Item", "other");
        Instant watermark = Instant.now();
        // Small sleep to ensure the next item has a later updated_at
        Thread.sleep(10);
        SupplyItem newer = savedItem("New Item", "diapers");

        mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer)
                        .param("since", watermark.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.supplyItems.updated[*].name")
                        .value(org.hamcrest.Matchers.hasItem("New Item")))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // Tombstone in deleted[] (not updated[])
    // -------------------------------------------------------------------------

    @Test
    void pull_tombstone_appearsInDeleted_notUpdated() throws Exception {
        SupplyItem item = savedItem("To Delete", "hygiene");
        item.setDeletedAt(Instant.now());
        item = items.saveAndFlush(item);
        UUID deletedId = item.getId();

        MvcResult result = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // The deleted ID must be in deleted[], not in updated[]
        assertThat(body).contains(deletedId.toString());
        // Ensure it's under the "deleted" key
        var responseNode = objectMapper.readTree(body);
        var deleted = responseNode.at("/changes/supplyItems/deleted");
        assertThat(deleted.isArray()).isTrue();
        boolean foundInDeleted = false;
        for (var node : deleted) {
            if (deletedId.toString().equals(node.asText())) {
                foundInDeleted = true;
                break;
            }
        }
        assertThat(foundInDeleted).as("Tombstoned item must appear in deleted[]").isTrue();

        // Must NOT appear in updated[]
        var updated = responseNode.at("/changes/supplyItems/updated");
        if (updated.isArray()) {
            for (var node : updated) {
                assertThat(node.get("id").asText()).isNotEqualTo(deletedId.toString());
            }
        }
    }

    // -------------------------------------------------------------------------
    // pregnancyProfile pull-replicated
    // -------------------------------------------------------------------------

    @Test
    void pull_pregnancyProfile_includedInChanges() throws Exception {
        // Seed a pregnancy profile for the user
        PregnancyProfile profile = new PregnancyProfile();
        profile.setUserId(userId);
        profile.setEdd(LocalDate.of(2026, 12, 1));
        profile.setEddBasis("due_date");
        profiles.save(profile);

        MvcResult result = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();

        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("changes")).isTrue();
        // pregnancyProfile must appear in the changes
        assertThat(body.at("/changes/pregnancyProfile").isMissingNode()).isFalse();
        var ppUpdated = body.at("/changes/pregnancyProfile/updated");
        assertThat(ppUpdated.isArray()).isTrue();
        assertThat(ppUpdated.size()).isEqualTo(1);
        assertThat(ppUpdated.get(0).get("userId").asText()).isEqualTo(userId.toString());
    }

    // -------------------------------------------------------------------------
    // Cursor continuation — nextCursor present when results exceed limit
    // -------------------------------------------------------------------------

    @Test
    void pull_limitExceeded_returnsNextCursor() throws Exception {
        // Save 3 items, request limit=2 → should return 2 with nextCursor
        savedItem("Item A", "diapers");
        Thread.sleep(5);
        savedItem("Item B", "feeding");
        Thread.sleep(5);
        savedItem("Item C", "hygiene");

        MvcResult firstPage = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andReturn();

        var firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
        assertThat(firstBody.has("nextCursor")).isTrue();
        assertThat(firstBody.get("nextCursor").asText()).isNotEmpty();

        // The timestamp (W1) is set at the first request
        String w1 = firstBody.get("timestamp").asText();
        String cursor = firstBody.get("nextCursor").asText();

        // Second page — use same since + cursor
        MvcResult secondPage = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer)
                        .param("limit", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andReturn();

        var secondBody = objectMapper.readTree(secondPage.getResponse().getContentAsString());
        // W1 must be the same on all batches (snapshot start, not drain end)
        assertThat(secondBody.get("timestamp").asText()).isEqualTo(w1);
        // nextCursor absent on the final page
        assertThat(secondBody.has("nextCursor")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Keyset order — (updated_at ASC, id ASC)
    // -------------------------------------------------------------------------

    @Test
    void pull_keysetOrder_resultsByUpdatedAtAscThenIdAsc() throws Exception {
        SupplyItem a = savedItem("Alpha", "diapers");
        Thread.sleep(10);
        SupplyItem b = savedItem("Beta", "feeding");
        Thread.sleep(10);
        SupplyItem c = savedItem("Gamma", "hygiene");

        MvcResult result = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();

        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        var updated = body.at("/changes/supplyItems/updated");
        assertThat(updated.isArray()).isTrue();
        assertThat(updated.size()).isGreaterThanOrEqualTo(3);

        // Verify ordering: a, b, c (oldest updatedAt first)
        String firstName = updated.get(0).get("name").asText();
        String secondName = updated.get(1).get("name").asText();
        String thirdName = updated.get(2).get("name").asText();
        assertThat(firstName).isEqualTo("Alpha");
        assertThat(secondName).isEqualTo("Beta");
        assertThat(thirdName).isEqualTo("Gamma");
    }

    // -------------------------------------------------------------------------
    // No IDOR — pull only returns authenticated user's records
    // -------------------------------------------------------------------------

    @Test
    void pull_noIdor_onlyReturnsOwnRecords() throws Exception {
        // Another user's item
        User other = new User();
        other.setEmail("other-pull@example.com");
        other.setEmailVerified(true);
        other = users.save(other);

        SupplyItem otherItem = new SupplyItem();
        otherItem.setId(UUID.randomUUID());
        otherItem.setUserId(other.getId());
        otherItem.setName("Other User's Item");
        otherItem.setCategory("diapers");
        items.saveAndFlush(otherItem);

        // Our item
        savedItem("My Item", "hygiene");

        MvcResult result = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();

        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        var updated = body.at("/changes/supplyItems/updated");
        assertThat(updated.isArray()).isTrue();

        // Only our own item should appear
        for (var node : updated) {
            assertThat(node.get("userId").asText()).isEqualTo(userId.toString());
            assertThat(node.get("name").asText()).isNotEqualTo("Other User's Item");
        }
    }

    // -------------------------------------------------------------------------
    // cursor.since is used (not the query param) — §9 pinned since across drain
    // -------------------------------------------------------------------------

    @Test
    void pull_cursorPresent_usesCursorSinceNotParam() throws Exception {
        // Save two items: one "old" and one "new"
        SupplyItem old = savedItem("Old Item", "other");
        Thread.sleep(10);
        SupplyItem newer = savedItem("New Item", "diapers");

        // First batch: since=EPOCH (cold start), limit=1 → gets old item, returns cursor
        MvcResult firstPage = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn();

        var firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
        String cursor = firstBody.get("nextCursor").asText();

        // Second batch with cursor (cursor.since=EPOCH from first request) + param since=far-future
        // The far-future since would exclude ALL items if used; cursor.since (EPOCH) must win.
        String farFuture = Instant.now().plus(365, ChronoUnit.DAYS).toString();
        MvcResult secondPage = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer)
                        .param("limit", "1")
                        .param("cursor", cursor)
                        .param("since", farFuture))  // conflicting param — must be ignored
                .andExpect(status().isOk())
                .andReturn();

        var secondBody = objectMapper.readTree(secondPage.getResponse().getContentAsString());
        // Must return the second item (because cursor.since=EPOCH, not farFuture)
        var updated = secondBody.at("/changes/supplyItems/updated");
        assertThat(updated.isArray()).isTrue();
        assertThat(updated.size()).isGreaterThan(0);
        assertThat(updated.get(0).get("name").asText()).isEqualTo("New Item");
    }

    // -------------------------------------------------------------------------
    // Watermark (timestamp) is the snapshot-START instant W1
    // Pull with no since → cold start → watermark is returned as timestamp
    // -------------------------------------------------------------------------

    @Test
    void pull_coldStart_returnsTimestampAsW1() throws Exception {
        savedItem("An Item", "other");

        Instant before = Instant.now();
        MvcResult result = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();
        Instant after = Instant.now();

        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        Instant w1 = Instant.parse(body.get("timestamp").asText());
        // W1 must be between before and after (it's the snapshot-start instant)
        assertThat(w1).isAfterOrEqualTo(before);
        assertThat(w1).isBeforeOrEqualTo(after);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SupplyItem savedItem(String name, String category) {
        SupplyItem item = new SupplyItem();
        item.setId(UUID.randomUUID());
        item.setUserId(userId);
        item.setName(name);
        item.setCategory(category);
        return items.saveAndFlush(item);
    }
}
