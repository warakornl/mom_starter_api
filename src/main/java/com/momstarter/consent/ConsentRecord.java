package com.momstarter.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only PDPA consent audit record.
 *
 * <p>Each row is an immutable grant or withdrawal event for one (user, consentType) pair.
 * The "current" consent state is determined by the latest row per (user_id, consent_type)
 * ordered by (granted_at DESC, granted ASC, id DESC) — withdrawal wins ties (fail-safe).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id}                   — server-generated UUID (never client-supplied)</li>
 *   <li>{@code userId}               — owning user (FK to {@code users})</li>
 *   <li>{@code consentType}          — one of 6 PDPA purposes (see CHECK in migration)</li>
 *   <li>{@code granted}              — {@code true} = grant, {@code false} = withdrawal</li>
 *   <li>{@code consentTextVersion}   — version tag of the consent text shown to the user</li>
 *   <li>{@code locale}               — normalised language ('th'|'en') from Accept-Language</li>
 *   <li>{@code grantedAt}            — server-authoritative timestamp (DEFAULT now())</li>
 *   <li>{@code createdAt}            — row-insertion instant (same as grantedAt in practice)</li>
 * </ul>
 *
 * <p>There is NO {@code deleted_at}, NO {@code version}, NO {@code updatedAt}: rows are
 * immutable after INSERT.  Withdrawal = new row with {@code granted=false}, not an UPDATE.
 *
 * <p>PDPA references: ม.19 (explicit consent + withdrawal); ม.26 (health data audit proof).
 *
 * @see ConsentRecordRepository
 */
@Entity
@Table(name = "consent_record")
public class ConsentRecord {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "consent_type", nullable = false, length = 32)
    private String consentType;

    @Column(nullable = false)
    private boolean granted;

    @Column(name = "consent_text_version", nullable = false, columnDefinition = "text")
    private String consentTextVersion;

    @Column(nullable = false, length = 8)
    private String locale;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (grantedAt == null) {
            grantedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }

    // -------------------------------------------------------------------------
    // Getters (no setters — rows are immutable after insert)
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getConsentType() {
        return consentType;
    }

    public boolean isGranted() {
        return granted;
    }

    public String getConsentTextVersion() {
        return consentTextVersion;
    }

    public String getLocale() {
        return locale;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // -------------------------------------------------------------------------
    // Setters — used only by ConsentService during construction before INSERT
    // -------------------------------------------------------------------------

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public void setConsentType(String consentType) {
        this.consentType = consentType;
    }

    public void setGranted(boolean granted) {
        this.granted = granted;
    }

    public void setConsentTextVersion(String consentTextVersion) {
        this.consentTextVersion = consentTextVersion;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }
}
