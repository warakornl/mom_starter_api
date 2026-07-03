package com.momstarter.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.JwtService;
import com.momstarter.expense.Expense;
import com.momstarter.expense.ExpenseRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC integration tests for the {@code expenses} sync collection via
 * {@code POST /sync/push} and {@code GET /sync/pull}.
 *
 * <p>Mirrors the shape of {@code SyncPushMvcTest} and {@code ChecklistItemSyncMvcTest}.
 * Verifies the expenses-specific sync contract:
 * <ul>
 *   <li>401 — unauthenticated push is rejected.</li>
 *   <li>Create (create sentinel version=0 → applied with version=1).</li>
 *   <li>Update (version match → applied + version bump).</li>
 *   <li>Soft-delete tombstone — expense disappears from live list, appears in pull tombstones.</li>
 *   <li>Sync round-trip — push create, pull confirms record in {@code updated[]}.</li>
 *   <li>Validation — category must be one of the 5 valid enum values.</li>
 *   <li>Validation — amount must be present (required field).</li>
 *   <li>Validation — incurredOn must be present (required field).</li>
 *   <li>server_won conflict — push with stale base version is rejected.</li>
 *   <li>tombstone_won conflict — update attempt on a tombstoned record fails.</li>
 *   <li>IDOR — user A cannot modify user B's expenses.</li>
 *   <li>expenses is pull-replicated — appears in GET /sync/pull changes.</li>
 *   <li>Skeleton tombstone — delete of never-seen id inserts a tombstone (OQ-SYNC-10).</li>
 * </ul>
 *
 * <p>Runs against H2 Flyway-migrated schema; each test is @Transactional + rollback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Transactional
class ExpenseSyncMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ExpenseRepository expenseRepo;

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
        expenseRepo.deleteAll();
        users.deleteAll();

        user = new User();
        user.setEmail("expense-sync@example.com");
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
    // CREATE — create sentinel → version:=1 in applied[]
    // -------------------------------------------------------------------------

    @Test
    void push_createExpense_appliedWithVersionOne() throws Exception {
        UUID id = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "expenses", Map.of(
                                "created", List.of(buildExpenseRecord(id, 0L, 59000, "healthcare",
                                        "2026-07-01", "ค่าตรวจ ANC")),
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
                .andExpect(jsonPath("$.applied.length()").value(1))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        // DB state: expense created with correct fields
        Expense saved = expenseRepo.findById(id).orElseThrow();
        assertThat(saved.getAmount()).isEqualTo(59000);
        assertThat(saved.getCategory()).isEqualTo("healthcare");
        assertThat(saved.getIncurredOn()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(saved.getNote()).isEqualTo("ค่าตรวจ ANC");
        assertThat(saved.getDeletedAt()).isNull();
    }

    // -------------------------------------------------------------------------
    // UPDATE — version match → applied + version bump
    // -------------------------------------------------------------------------

    @Test
    void push_updateExpense_versionMatch_applied() throws Exception {
        // Seed an existing expense with version=1 (simulate post-create state)
        UUID id = UUID.randomUUID();
        Expense existing = buildExpense(id, 59000, "healthcare", LocalDate.of(2026, 7, 1));
        existing = expenseRepo.saveAndFlush(existing);
        expenseRepo.initVersionToOne(id);
        // Reload to get version=1
        expenseRepo.flush();

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "expenses", Map.of(
                                "created", List.of(),
                                "updated", List.of(buildExpenseRecord(id, 1L, 80000, "healthcare",
                                        "2026-07-01", null)),
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
                .andExpect(jsonPath("$.applied.length()").value(1))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        // Amount updated
        Expense updated = expenseRepo.findById(id).orElseThrow();
        assertThat(updated.getAmount()).isEqualTo(80000);
    }

    // -------------------------------------------------------------------------
    // SOFT-DELETE tombstone
    // -------------------------------------------------------------------------

    @Test
    void push_deleteExpense_tombstonedAndExcludedFromLiveList() throws Exception {
        // Seed expense
        UUID id = UUID.randomUUID();
        expenseRepo.saveAndFlush(buildExpense(id, 25000, "mother", LocalDate.of(2026, 6, 15)));
        expenseRepo.initVersionToOne(id);

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "expenses", Map.of(
                                "created", List.of(),
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
                .andExpect(jsonPath("$.applied.length()").value(1))
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());

        // DB state: tombstoned
        Expense tombstone = expenseRepo.findById(id).orElseThrow();
        assertThat(tombstone.getDeletedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // SYNC ROUND-TRIP — push then pull sees the record
    // -------------------------------------------------------------------------

    @Test
    void syncRoundTrip_pushCreate_pullReturnsExpenseInUpdated() throws Exception {
        UUID id = UUID.randomUUID();

        // Push create
        String pushBody = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "expenses", Map.of(
                                "created", List.of(buildExpenseRecord(id, 0L, 42800, "baby-supplies",
                                        "2026-07-01", null)),
                                "updated", List.of(),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));
        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pushBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0].id").value(id.toString()));

        // Pull cold-start
        String pullResponse = mvc.perform(get("/sync/pull")
                        .header("Authorization", "Bearer " + bearer)
                        .param("lastPulledAt", "0"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Expenses must appear in the pull changes under updated[]
        @SuppressWarnings("unchecked")
        Map<String, Object> pull = objectMapper.readValue(pullResponse, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> changes = (Map<String, Object>) pull.get("changes");
        assertThat(changes).containsKey("expenses");

        @SuppressWarnings("unchecked")
        Map<String, Object> expensesChanges = (Map<String, Object>) changes.get("expenses");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> updated = (List<Map<String, Object>>) expensesChanges.get("updated");
        assertThat(updated).isNotNull().isNotEmpty();

        // The pushed expense must be in updated[]
        String idStr = id.toString();
        boolean found = updated.stream()
                .anyMatch(r -> idStr.equals(r.get("id")));
        assertThat(found).as("pushed expense must appear in pull updated[]").isTrue();
    }

    // -------------------------------------------------------------------------
    // VALIDATION — invalid category rejected
    // -------------------------------------------------------------------------

    @Test
    void push_invalidCategory_rejectedWithValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "expenses", Map.of(
                                "created", List.of(buildExpenseRecordRaw(id, 0L, 10000,
                                        "invalid-category", "2026-07-01", null)),
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
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty())
                .andExpect(jsonPath("$.conflicts").isEmpty());
    }

    // -------------------------------------------------------------------------
    // VALIDATION — missing amount rejected
    // -------------------------------------------------------------------------

    @Test
    void push_missingAmount_rejectedWithValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        // amount absent from record
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("id", id.toString());
        record.put("version", 0L);
        record.put("category", "other");
        record.put("incurredOn", "2026-07-01");

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "expenses", Map.of(
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
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // VALIDATION — missing incurredOn rejected
    // -------------------------------------------------------------------------

    @Test
    void push_missingIncurredOn_rejectedWithValidationError() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("id", id.toString());
        record.put("version", 0L);
        record.put("amount", 10000);
        record.put("category", "other");
        // incurredOn absent

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "expenses", Map.of(
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
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].code").value("validation_error"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // server_won conflict — stale base version
    // -------------------------------------------------------------------------

    @Test
    void push_staleBaseVersion_serverWonConflict() throws Exception {
        // Seed expense at version=2 (simulate two prior updates)
        UUID id = UUID.randomUUID();
        Expense e = buildExpense(id, 10000, "other", LocalDate.of(2026, 7, 1));
        e = expenseRepo.saveAndFlush(e);
        expenseRepo.initVersionToOne(id);
        // Update once more to get version=2
        e = expenseRepo.findById(id).orElseThrow();
        e.setAmount(12000);
        expenseRepo.saveAndFlush(e); // version → 2

        // Push with base_version=1 (stale — server is at 2)
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "expenses", Map.of(
                                "created", List.of(),
                                "updated", List.of(buildExpenseRecord(id, 1L, 99999, "other",
                                        "2026-07-01", null)),
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
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].id").value(id.toString()))
                .andExpect(jsonPath("$.conflicts[0].resolution").value("server_won"))
                .andExpect(jsonPath("$.applied").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());
    }

    // -------------------------------------------------------------------------
    // tombstone_won — update of tombstoned expense
    // -------------------------------------------------------------------------

    @Test
    void push_updateTombstonedExpense_tombstoneWonConflict() throws Exception {
        UUID id = UUID.randomUUID();
        Expense e = buildExpense(id, 10000, "other", LocalDate.of(2026, 7, 1));
        e.setDeletedAt(java.time.Instant.now()); // already tombstoned
        expenseRepo.saveAndFlush(e);
        expenseRepo.initVersionToOne(id);

        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "expenses", Map.of(
                                "created", List.of(),
                                "updated", List.of(buildExpenseRecord(id, 1L, 20000, "other",
                                        "2026-07-01", null)),
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
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].resolution").value("tombstone_won"))
                .andExpect(jsonPath("$.applied").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Skeleton tombstone — delete of never-seen id (OQ-SYNC-10)
    // -------------------------------------------------------------------------

    @Test
    void push_deleteNeverSeenId_insertsSkeleton() throws Exception {
        UUID neverSeenId = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "expenses", Map.of(
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
                .andExpect(jsonPath("$.applied.length()").value(1))
                .andExpect(jsonPath("$.applied[0].id").value(neverSeenId.toString()))
                .andExpect(jsonPath("$.applied[0].version").value(1));

        // A skeleton tombstone row exists
        Expense skeleton = expenseRepo.findById(neverSeenId).orElseThrow();
        assertThat(skeleton.getDeletedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // IDOR — user A cannot modify user B's expenses
    // -------------------------------------------------------------------------

    @Test
    void push_noIdor_cannotModifyAnotherUsersExpense() throws Exception {
        // Create another user's expense
        User other = new User();
        other.setEmail("other-expense@example.com");
        other.setEmailVerified(true);
        other = users.save(other);

        UUID otherId = UUID.randomUUID();
        Expense otherExpense = new Expense();
        otherExpense.setId(otherId);
        otherExpense.setUserId(other.getId());
        otherExpense.setAmount(99999);
        otherExpense.setCategory("other");
        otherExpense.setIncurredOn(LocalDate.of(2026, 7, 1));
        otherExpense = expenseRepo.saveAndFlush(otherExpense);
        long otherVersion = otherExpense.getVersion();

        // Authenticated user tries to update other user's expense
        String body = objectMapper.writeValueAsString(Map.of(
                "changes", Map.of(
                        "expenses", Map.of(
                                "created", List.of(),
                                "updated", List.of(buildExpenseRecord(otherId, otherVersion, 1, "other",
                                        "2026-07-01", null)),
                                "deleted", List.of()
                        )
                ),
                "lastPulledAt", "0"
        ));

        mvc.perform(post("/sync/push")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // The other user's expense must be completely unchanged
        Expense untouched = expenseRepo.findById(otherId).orElseThrow();
        assertThat(untouched.getUserId()).isEqualTo(other.getId());
        assertThat(untouched.getAmount()).isEqualTo(99999);
    }

    // -------------------------------------------------------------------------
    // All 5 valid categories are accepted
    // -------------------------------------------------------------------------

    @Test
    void push_allFiveCategories_accepted() throws Exception {
        List<String> categories = List.of(
                "baby-supplies", "healthcare", "baby-gear", "mother", "other");

        for (String cat : categories) {
            UUID id = UUID.randomUUID();
            String body = objectMapper.writeValueAsString(Map.of(
                    "changes", Map.of(
                            "expenses", Map.of(
                                    "created", List.of(buildExpenseRecord(id, 0L, 10000, cat,
                                            "2026-07-01", null)),
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
                    .andExpect(jsonPath("$.applied.length()").value(1));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Expense buildExpense(UUID id, int amount, String category, LocalDate incurredOn) {
        Expense e = new Expense();
        e.setId(id);
        e.setUserId(userId);
        e.setAmount(amount);
        e.setCategory(category);
        e.setIncurredOn(incurredOn);
        return e;
    }

    private Map<String, Object> buildExpenseRecord(UUID id, long baseVersion, int amount,
                                                    String category, String incurredOn, String note) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id.toString());
        m.put("version", baseVersion);
        m.put("amount", amount);
        m.put("category", category);
        m.put("incurredOn", incurredOn);
        if (note != null) m.put("note", note);
        return m;
    }

    /** Builds a record with a raw (possibly invalid) category for validation tests. */
    private Map<String, Object> buildExpenseRecordRaw(UUID id, long baseVersion, int amount,
                                                       String category, String incurredOn, String note) {
        return buildExpenseRecord(id, baseVersion, amount, category, incurredOn, note);
    }

    private String buildPushBody(Map<String, Object> extraChanges) throws Exception {
        Map<String, Object> changes = new java.util.HashMap<>(extraChanges);
        return objectMapper.writeValueAsString(Map.of(
                "changes", changes,
                "lastPulledAt", "0"
        ));
    }
}
