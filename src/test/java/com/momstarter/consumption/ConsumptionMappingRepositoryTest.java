package com.momstarter.consumption;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link ConsumptionMapping} / {@link ConsumptionMappingRepository}.
 *
 * <p>Runs against the real Flyway-migrated schema (H2 PostgreSQL mode).
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Client-generated id preserved on save.</li>
 *   <li>All valid activity_type values accepted; unknown value rejected by DB CHECK.</li>
 *   <li>supply_item_id is nullable (soft reference — NO FK). NULL accepted.</li>
 *   <li>default_qty=0 accepted (no-op mapping, not rejected).</li>
 *   <li>default_qty negative rejected by DB CHECK (≥0).</li>
 *   <li>enabled=true/false round-trips.</li>
 *   <li>Sync-pull keyset query: {@link ConsumptionMappingRepository#findForPull}.</li>
 *   <li>Cursor continuation: {@link ConsumptionMappingRepository#findForPullAfterCursor}.</li>
 *   <li>Tombstone visible in pull query; live-list query excludes tombstones.</li>
 *   <li>INV-ASD-9: entity is health-side (no FK to supply_items, no FK cascade).</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=true")
class ConsumptionMappingRepositoryTest {

    @Autowired
    private ConsumptionMappingRepository mappings;

    @Autowired
    private UserRepository users;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private User savedUser(String email) {
        User u = new User();
        u.setEmail(email);
        return users.save(u);
    }

    private ConsumptionMapping buildMapping(UUID userId, String activityType) {
        ConsumptionMapping m = new ConsumptionMapping();
        m.setId(UUID.randomUUID());
        m.setUserId(userId);
        m.setActivityType(activityType);
        m.setDefaultQty(1);
        m.setEnabled(true);
        return m;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void clientSuppliedId_preserved_onSave() {
        User u = savedUser("cm-id@example.com");
        UUID clientId = UUID.fromString("cccccccc-dddd-eeee-ffff-000000000001");

        ConsumptionMapping m = buildMapping(u.getId(), "diaper_change");
        m.setId(clientId);
        mappings.saveAndFlush(m);

        assertThat(mappings.findById(clientId)).isPresent();
    }

    @Test
    void feedingFormulaActivityType_savesAndRetrieves() {
        User u = savedUser("cm-formula@example.com");

        ConsumptionMapping m = buildMapping(u.getId(), "feeding_formula");
        m.setSupplyItemId(UUID.randomUUID()); // soft ref — no FK
        m.setDefaultQty(4);
        mappings.saveAndFlush(m);

        List<ConsumptionMapping> found = mappings.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(found).hasSize(1);
        ConsumptionMapping f = found.get(0);
        assertThat(f.getActivityType()).isEqualTo("feeding_formula");
        assertThat(f.getDefaultQty()).isEqualTo(4);
        assertThat(f.getSupplyItemId()).isNotNull();
        assertThat(f.isEnabled()).isTrue();
        assertThat(f.getCreatedAt()).isNotNull();
        assertThat(f.getUpdatedAt()).isNotNull();
        assertThat(f.getDeletedAt()).isNull();
    }

    @Test
    void diaperChangeActivityType_savesAndRetrieves() {
        User u = savedUser("cm-diaper@example.com");
        ConsumptionMapping m = buildMapping(u.getId(), "diaper_change");
        mappings.saveAndFlush(m);

        ConsumptionMapping loaded = mappings.findById(m.getId()).orElseThrow();
        assertThat(loaded.getActivityType()).isEqualTo("diaper_change");
    }

    @Test
    void bathingActivityType_savesAndRetrieves() {
        User u = savedUser("cm-bath@example.com");
        ConsumptionMapping m = buildMapping(u.getId(), "bathing");
        mappings.saveAndFlush(m);

        ConsumptionMapping loaded = mappings.findById(m.getId()).orElseThrow();
        assertThat(loaded.getActivityType()).isEqualTo("bathing");
    }

    @Test
    void unknownActivityType_rejectedByDbConstraint() {
        User u = savedUser("cm-unknown@example.com");
        ConsumptionMapping m = buildMapping(u.getId(), "breastfeed"); // invalid

        assertThatThrownBy(() -> {
            mappings.save(m);
            mappings.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void supplyItemId_nullable_accepted() {
        User u = savedUser("cm-null-ref@example.com");

        ConsumptionMapping m = buildMapping(u.getId(), "diaper_change");
        m.setSupplyItemId(null); // allowed: soft reference, may be temporarily null
        mappings.saveAndFlush(m);

        ConsumptionMapping loaded = mappings.findById(m.getId()).orElseThrow();
        assertThat(loaded.getSupplyItemId()).isNull();
    }

    @Test
    void defaultQty_zero_accepted() {
        // 0 = no-op mapping (preserves the link but draws nothing — edge case, allowed)
        User u = savedUser("cm-zero@example.com");
        ConsumptionMapping m = buildMapping(u.getId(), "diaper_change");
        m.setDefaultQty(0);
        mappings.saveAndFlush(m);

        ConsumptionMapping loaded = mappings.findById(m.getId()).orElseThrow();
        assertThat(loaded.getDefaultQty()).isEqualTo(0);
    }

    @Test
    void defaultQty_negative_rejectedByDbConstraint() {
        User u = savedUser("cm-neg@example.com");
        ConsumptionMapping m = buildMapping(u.getId(), "diaper_change");
        m.setDefaultQty(-1); // violates CHECK (default_qty >= 0)

        assertThatThrownBy(() -> {
            mappings.save(m);
            mappings.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enabled_false_savesAndRetrieves() {
        User u = savedUser("cm-disabled@example.com");
        ConsumptionMapping m = buildMapping(u.getId(), "diaper_change");
        m.setEnabled(false);
        mappings.saveAndFlush(m);

        ConsumptionMapping loaded = mappings.findById(m.getId()).orElseThrow();
        assertThat(loaded.isEnabled()).isFalse();
    }

    @Test
    void tombstone_visibleInPullQuery_excludedFromLiveQuery() {
        User u = savedUser("cm-tombstone@example.com");

        ConsumptionMapping m = buildMapping(u.getId(), "diaper_change");
        mappings.saveAndFlush(m);
        m.setDeletedAt(Instant.now());
        mappings.saveAndFlush(m);

        // Pull includes tombstones
        List<ConsumptionMapping> pull = mappings.findForPull(u.getId(), Instant.EPOCH, Pageable.unpaged());
        assertThat(pull).hasSize(1);
        assertThat(pull.get(0).getDeletedAt()).isNotNull();

        // Live query excludes tombstones
        List<ConsumptionMapping> live = mappings.findByUserIdAndDeletedAtIsNull(u.getId());
        assertThat(live).isEmpty();
    }

    @Test
    void cursorContinuation_resumesAfterGivenPosition() {
        User u = savedUser("cm-cursor@example.com");

        ConsumptionMapping a = buildMapping(u.getId(), "diaper_change");
        a = mappings.saveAndFlush(a);

        ConsumptionMapping b = buildMapping(u.getId(), "bathing");
        b = mappings.saveAndFlush(b);

        // Update a so it has the latest updatedAt
        a.setDefaultQty(5);
        a = mappings.saveAndFlush(a);

        // Capture IDs into effectively-final locals (a and b are reassigned above).
        final UUID aId = a.getId();
        final UUID bId = b.getId();

        List<ConsumptionMapping> afterCursor = mappings.findForPullAfterCursor(
                u.getId(), Instant.EPOCH,
                b.getUpdatedAt(), bId,
                Pageable.ofSize(100));

        assertThat(afterCursor).anyMatch(m -> m.getId().equals(aId));
        assertThat(afterCursor).noneMatch(m -> m.getId().equals(bId));
    }

    @Test
    void pullQuery_isolatesResultsByUserId() {
        User alice = savedUser("alice-cm@example.com");
        User bob = savedUser("bob-cm@example.com");

        mappings.saveAndFlush(buildMapping(alice.getId(), "diaper_change"));
        mappings.saveAndFlush(buildMapping(bob.getId(), "bathing"));

        List<ConsumptionMapping> aliceMappings = mappings.findForPull(alice.getId(), Instant.EPOCH, Pageable.unpaged());
        List<ConsumptionMapping> bobMappings = mappings.findForPull(bob.getId(), Instant.EPOCH, Pageable.unpaged());

        assertThat(aliceMappings).hasSize(1);
        assertThat(aliceMappings.get(0).getUserId()).isEqualTo(alice.getId());
        assertThat(bobMappings).hasSize(1);
        assertThat(bobMappings.get(0).getUserId()).isEqualTo(bob.getId());
    }

    /**
     * INV-ASD-9: ConsumptionMapping is a HEALTH-SIDE entity.
     * supply_item_id is a SOFT REFERENCE — no FK constraint, no ON DELETE CASCADE.
     * Deleting a hypothetical supply_items row does NOT cascade here (no FK to violate).
     * This test verifies the entity has no declared FK annotation to supply_items.
     */
    @Test
    void inv_asd9_supplyItemIdIsSoftReferenceNoPersistenceFk() {
        // The @Column annotation for supplyItemId must NOT carry @ManyToOne or @JoinColumn
        var field = java.util.Arrays.stream(ConsumptionMapping.class.getDeclaredFields())
                .filter(f -> f.getName().equals("supplyItemId"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("supplyItemId field must exist"));

        assertThat(field.isAnnotationPresent(jakarta.persistence.ManyToOne.class)).isFalse();
        assertThat(field.isAnnotationPresent(jakarta.persistence.JoinColumn.class)).isFalse();
    }
}
