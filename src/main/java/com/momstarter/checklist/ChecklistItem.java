package com.momstarter.checklist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ChecklistItem — mark-done calendar entry (data-model §3.4, US-12).
 *
 * <p>Capture type (c): covers ANC visits, labs, screenings, vaccines, hospital-bag tasks,
 * postpartum checks, AND user appointments ({@code category=appointment} — single entity
 * keeps capture type (c) DRY; see technical-decisions.md §7).
 *
 * <p>MOTHER-health collection; gated by {@code general_health} (per-collection) +
 * {@code cloud_storage} (whole-batch) on {@code sync/push} / {@code sync/pull}.
 * All mutations flow through {@code POST /sync/push} (collection {@code checklistItems}).
 *
 * <h3>Appointment field model (OQ-CAL-1 R-A — PINNED)</h3>
 * <p>Location / doctor / clinic / phone have NO structured column in MVP.
 * They are folded into the free-text {@link #note} (and/or {@link #title}), unstructured,
 * NEVER parsed (G4).  R-B (structured fields, possibly client-encrypted) is DEFERRED.
 *
 * <h3>id is CLIENT-GENERATED</h3>
 * <p>No {@code @GeneratedValue}. Spring Data JPA's {@code isNew()} uses the
 * {@link Long} wrapper {@link #version} (null before first persist).
 *
 * <h3>Floating-civil scheduled_at (FLAG-1)</h3>
 * <p>{@link #scheduledAt} is a nullable floating-civil calendar bucket key
 * ({@link LocalDateTime} → {@code timestamp WITHOUT TIME ZONE}).
 * {@code null} for undated checklist tasks (hospital-bag items etc.).
 * {@code appointment}/{@code anc_visit} items are always dated — CLIENT-ENFORCED; the server
 * stores {@code scheduledAt} verbatim (no DB-level per-category NOT NULL).
 *
 * <h3>Note is NEVER PARSED (G4)</h3>
 * <p>{@link #note} is the capture vehicle for lab/screening results (SD-7), appointment
 * location/doctor notes (OQ-CAL-1 R-A), and any other free-text the mother enters.
 * The server stores it verbatim and echoes it back; no parsing, no thresholding.
 */
@Entity
@Table(name = "checklist_items")
public class ChecklistItem {

    /**
     * Client-generated UUIDv4.  No {@code @GeneratedValue}.
     * Spring Data JPA's {@code isNew()} uses {@link #version} (null before first persist).
     */
    @Id
    private UUID id;

    /** Owner user. FK → {@code users(id)}. NOT NULL. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Capture-type-c kind of this item.  DB CHECK constrains to:
     * {@code appointment | anc_visit | lab_panel | screening | vaccine |
     * checklist_task | postpartum_check}.
     */
    @Column(nullable = false)
    private String category;

    /**
     * Free-text label.  Language-agnostic; stored verbatim; never parsed.
     */
    @Column(nullable = false)
    private String title;

    /**
     * Floating-civil calendar bucket key (FLAG-1).
     * {@link LocalDateTime} → {@code timestamp WITHOUT TIME ZONE} — never UTC-normalized.
     * {@code null} for undated tasks (hospital-bag items, generic checklists).
     * {@code appointment}/{@code anc_visit} items are always dated (client-enforced; not DB CHECK).
     */
    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    /**
     * Completion flag.  {@code false} = open, {@code true} = done.  Default {@code false}.
     */
    @Column(nullable = false)
    private boolean done = false;

    /**
     * Absolute-UTC server-clock instant when marked done.  {@code null} while {@code done = false}.
     * This is a system/action instant (NOT a bucket key) → stored as {@code timestamptz}.
     */
    @Column(name = "done_at")
    private Instant doneAt;

    /**
     * Free-text note.  NEVER parsed (G4).
     * SD-7: this is how lab/screening "results" and observation notes are captured.
     * OQ-CAL-1 R-A: location/doctor/clinic details fold into this field.
     * Language-agnostic; stored verbatim; echoed back only.  Nullable.
     */
    @Column
    private String note;

    /**
     * How this item was created.  DB CHECK: {@code user_created | from_suggestion}.
     * Default {@code user_created}.
     */
    @Column(nullable = false)
    private String source = "user_created";

    /**
     * Soft link to the {@code UserSuggestionState} that spawned this item (US-8/9 Start flow).
     * {@code null} for {@code user_created} items.  NO FK.
     */
    @Column(name = "source_suggestion_state_id")
    private UUID sourceSuggestionStateId;

    // -------------------------------------------------------------------------
    // <sync> block (data-model §1.2 / §2)
    // -------------------------------------------------------------------------

    /**
     * Server-assigned optimistic-concurrency token.  {@link Long} wrapper for Spring Data
     * JPA's {@code isNew()} detection (null before first persist).
     */
    @Version
    private Long version;

    /** Server-assigned on first INSERT; never changed after. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Server-assigned on EVERY apply.  The sole LWW merge clock. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone.  {@code null} = live.  {@code non-null} = tombstoned.
     * Tombstone-wins unconditionally (sync spec §A.5).
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Originating device UUID.  LWW tie-break only (sync spec §A.6). */
    @Column(name = "client_id")
    private UUID clientId;

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }

    public Instant getDoneAt() { return doneAt; }
    public void setDoneAt(Instant doneAt) { this.doneAt = doneAt; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public UUID getSourceSuggestionStateId() { return sourceSuggestionStateId; }
    public void setSourceSuggestionStateId(UUID sourceSuggestionStateId) {
        this.sourceSuggestionStateId = sourceSuggestionStateId;
    }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
}
