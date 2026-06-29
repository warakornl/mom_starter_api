package com.momstarter.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.checklist.ChecklistItem;
import com.momstarter.checklist.ChecklistItemRepository;
import com.momstarter.pregnancy.ConsentChecker;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for the {@code checklistItems} sync collection.
 *
 * <p>Covers (api-contract B1 / data-model §3.4):
 * <ul>
 *   <li>create checklist item → applied[] (version:=1)</li>
 *   <li>LWW conflict (base &lt; current) → conflicts[server_won]</li>
 *   <li>tombstone → applied[]</li>
 *   <li>general_health consent gate → rejected[consent_required] via @MockBean</li>
 *   <li>pull includes checklistItems collection in changes</li>
 * </ul>
 *
 * <p>Appointment date/time requiredness (OQ-CAL-2) is a CLIENT rule — the server stores
 * {@code scheduledAt} verbatim; no server-side per-category NOT-NULL validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class ChecklistItemSyncMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private ChecklistItemRepository checklistItems;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @MockBean
    private ConsentChecker consentChecker;

    private User user;
    private String bearer;
    private UUID userId;

    @BeforeEach
    void setup() {
        checklistItems.deleteAll();
        users.deleteAll();
        user = new User();
        user.setEmail("checklist-sync@example.com");
        user.setEmailVerified(true);
        user = users.save(user);
        userId = user.getId();
        bearer = jwtService.issueAccessToken(userId, true);
        when(consentChecker.isGranted(any(UUID.class), any(String.class))).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // Create — new checklist item → applied[]
    // -------------------------------------------------------------------------

    @Test
    void push_createChecklistItem_applied() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildChecklistRecord(id, 0L,
                "ANC visit week 28", "anc_visit", "2026-07-15T09:00", false);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("checklistItems"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        ChecklistItem saved = checklistItems.findById(id).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getTitle()).isEqualTo("ANC visit week 28");
        assertThat(saved.getCategory()).isEqualTo("anc_visit");
        assertThat(saved.getVersion()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // LWW conflict — base < current → server_won
    // -------------------------------------------------------------------------

    @Test
    void push_staleVersion_serverWonConflict() throws Exception {
        UUID id = UUID.randomUUID();
        ChecklistItem existing = seedChecklistItem(id, "Lab panel", "lab_panel");
        long currentVersion = existing.getVersion(); // 1

        Map<String, Object> record = buildChecklistRecord(id, currentVersion - 1,
                "Stale edit", "lab_panel", null, false);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(), List.of(record), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts[0].collection").value("checklistItems"))
                .andExpect(jsonPath("$.conflicts[0].id").value(id.toString()))
                .andExpect(jsonPath("$.conflicts[0].resolution").value("server_won"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Tombstone — delete applied unconditionally
    // -------------------------------------------------------------------------

    @Test
    void push_delete_tombstoned() throws Exception {
        UUID id = UUID.randomUUID();
        seedChecklistItem(id, "Glucose test", "lab_panel");

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(), List.of(), List.of(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].collection").value("checklistItems"))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        assertThat(checklistItems.findById(id).orElseThrow().getDeletedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Consent gate — general_health absent → rejected[consent_required]
    // -------------------------------------------------------------------------

    @Test
    void push_generalHealthConsentAbsent_rejectedConsentRequired() throws Exception {
        when(consentChecker.isGranted(eq(userId), eq("cloud_storage"))).thenReturn(true);
        when(consentChecker.isGranted(eq(userId), eq("general_health"))).thenReturn(false);

        UUID id = UUID.randomUUID();
        Map<String, Object> record = buildChecklistRecord(id, 0L, "No consent",
                "checklist_task", null, false);

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPushBody(List.of(record), List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].collection").value("checklistItems"))
                .andExpect(jsonPath("$.rejected[0].code").value("consent_required"))
                .andExpect(jsonPath("$.rejected[0].details").value("general_health"));
    }

    // -------------------------------------------------------------------------
    // Pull — checklistItems collection appears in changes
    // -------------------------------------------------------------------------

    @Test
    void pull_includesChecklistItems() throws Exception {
        seedChecklistItem(UUID.randomUUID(), "Hospital bag", "checklist_task");

        MvcResult result = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        var responseNode = objectMapper.readTree(body);
        assertThat(responseNode.at("/changes/checklistItems").isMissingNode()).isFalse();
        var updated = responseNode.at("/changes/checklistItems/updated");
        assertThat(updated.isArray()).isTrue();
        assertThat(updated.size()).isGreaterThan(0);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Map<String, Object> buildChecklistRecord(UUID id, long version, String title,
                                                     String category, String scheduledAt,
                                                     boolean done) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id.toString());
        r.put("version", version);
        r.put("title", title);
        r.put("category", category);
        r.put("scheduledAt", scheduledAt);
        r.put("done", done);
        r.put("source", "user_created");
        r.put("clientId", UUID.randomUUID().toString());
        return r;
    }

    private String buildPushBody(List<Map<String, Object>> created,
                                  List<Map<String, Object>> updated, List<String> deleted)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "checklistItems", Map.of(
                                "created", created,
                                "updated", updated,
                                "deleted", deleted
                        )
                ),
                "lastPulledAt", "0"
        ));
    }

    /** Seeds a checklist item directly into the DB (bypasses push path). */
    private ChecklistItem seedChecklistItem(UUID id, String title, String category) {
        ChecklistItem item = new ChecklistItem();
        item.setId(id);
        item.setUserId(userId);
        item.setTitle(title);
        item.setCategory(category);
        item.setDone(false);
        item.setSource("user_created");
        item = checklistItems.saveAndFlush(item);
        checklistItems.initVersionToOne(item.getId());
        return checklistItems.findById(id).orElseThrow();
    }
}
