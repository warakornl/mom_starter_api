package com.momstarter.pregnancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * PregnancyProfile — drives the calendar/stage for the whole pregnancy companion app
 * (data-model §3.1).
 *
 * <p>One profile per user (UNIQUE {@code user_id}). Written via
 * {@code PUT /pregnancy-profile} (direct-REST, not sync/push); pull-replicated to all
 * user devices via {@code sync/pull}.
 *
 * <p>Derived fields ({@code gestationalWeek}, {@code currentStage}, {@code daysRemaining},
 * {@code progress}, {@code deliveryWindowActive}) are <strong>never stored</strong> — they
 * are computed at runtime from {@code edd} using the canonical algorithm pinned in
 * data-model §3.1 "Canonical gestational-age &amp; stage computation (PINNED)".
 *
 * <p>PDPA: {@code edd} is health data (PDPA s.26 sensitive personal data). At-rest
 * encryption is handled at the RDS/KMS layer in production.
 */
@Entity
@Table(name = "pregnancy_profile")
public class PregnancyProfile {

    @Id
    private UUID id;

    /**
     * Owner user. UNIQUE — enforces the one-profile-per-user rule (data-model §3.1).
     */
    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    /**
     * Estimated due date — the single stored civil date anchor (zoneless, proleptic
     * Gregorian — FLAG-1). Java {@link LocalDate} maps to DB {@code date} (no time, no
     * timezone). Never null. Not a derived field. Health data (PDPA s.26).
     */
    @Column(nullable = false)
    private LocalDate edd;

    /**
     * How EDD was supplied at input time.
     * <ul>
     *   <li>{@code "due_date"} — entered directly as a civil date.</li>
     *   <li>{@code "current_week"} — back-computed from a gestational-week input (OQ-7):
     *       {@code edd = today + (280 − N*7)}.</li>
     * </ul>
     */
    @Column(name = "edd_basis", nullable = false, length = 16)
    private String eddBasis;

    /**
     * Lifecycle state of this pregnancy.
     * MVP ships {@code "pregnant"} only. {@code "postpartum"} and {@code "ended"} are
     * set by the deferred {@code POST /pregnancy-profile/birth-event} phase.
     */
    @Column(nullable = false, length = 16)
    private String lifecycle = "pregnant";

    /**
     * Floating-civil birth date (zoneless {@code YYYY-MM-DD} — FLAG-1 / data-model §3.1 OQ-11).
     * {@code null} while {@code lifecycle = "pregnant"}. Set by
     * {@code POST /pregnancy-profile/birth-event} (deferred endpoint).
     *
     * <p>The postpartum clock is computed civil-day-wise from this anchor:
     * {@code postpartumDays = max(0, civilDaysBetween(birthDate, today))} — identical rule to the
     * gestational week counter (FLAG-1). Time-of-day of birth is not part of this anchor
     * (it belongs to a future {@code BabyProfile} birth record if ever needed).
     *
     * <p>Java {@link LocalDate} maps to DB {@code date} (no time, no timezone).
     */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    /**
     * Floating-civil loss date (zoneless {@code YYYY-MM-DD} — FLAG-1 / data-model §5 "Pregnancy-loss
     * lifecycle transition &amp; reminder tombstones" L275). {@code null} unless
     * {@code lifecycle = "ended"} and the mother chose to record a date (OPTIONAL/skippable —
     * the system needs only {@code lifecycle='ended'} to stop content/push).
     *
     * <p>Set by {@code POST /pregnancy-profile/loss-event}; cleared to {@code null} in the
     * <strong>same transaction</strong> as {@code POST /pregnancy-profile/reopen} (S4/ม.35/ม.36 —
     * a stale loss date on a now-{@code pregnant} profile is inaccurate and purpose-less).
     *
     * <p>PDPA: <strong>plaintext</strong> — NOT on the encrypted-column list (data-model L511 /
     * pdpa-assessment S6: date-only, no time-of-day, no metadata). {@code lifecycle}/{@code
     * lossDate} must never enter any ad/product/targeting query (LOSS-INV-10 / L-15.7 / Z-8 —
     * permanent lock).
     *
     * <p>Java {@link LocalDate} maps to DB {@code date} (no time, no timezone). No DB CHECK for
     * bounds — validation ({@code loss_date_range} / {@code loss_date_malformed}) is
     * application-layer, relative to the client civil "today" and {@code edd}, same posture as
     * {@link #birthDate}.
     */
    @Column(name = "loss_date")
    private LocalDate lossDate;

    /**
     * Mother's first name — {@code bytea} ciphertext (client-encrypted identity PII,
     * name-fields-design.md Option A). MVP posture: holds plaintext bytes (no-op cipher).
     * Real AES-256-GCM lands in the same column at the KMS/EAS milestone (zero schema change).
     * Nullable — all three name fields are optional (name-fields-design.md Decision 3).
     * Never queried or parsed server-side; only stored and echoed back as Base64.
     * Crypto-shredded to NULL by {@link PregnancyProfileRepository#shredCiphersByUserId} at T0.
     */
    @Column(name = "mother_first_name_cipher", columnDefinition = "bytea")
    private byte[] motherFirstNameCipher;

    /**
     * Mother's last name — same posture as {@link #motherFirstNameCipher}.
     * Nullable; crypto-shredded to NULL at T0 alongside motherFirstNameCipher.
     */
    @Column(name = "mother_last_name_cipher", columnDefinition = "bytea")
    private byte[] motherLastNameCipher;

    /**
     * Baby's name — same posture as {@link #motherFirstNameCipher}.
     * Nullable; capturable anytime (pre- or post-birth, owner decision OQ-N-OWN3).
     * Crypto-shredded to NULL at T0.
     */
    @Column(name = "baby_name_cipher", columnDefinition = "bytea")
    private byte[] babyNameCipher;

    /**
     * Hospital admission date — {@code bytea} ciphertext (client-encrypted delivery-record PII,
     * pregnancy-summary-design.md §1.2 Option A). MVP posture: holds plaintext date bytes (no-op
     * cipher). Real AES-256-GCM lands in the same column at the KMS/EAS milestone (zero schema
     * change). Nullable — both hospital date fields are optional (pregnancy-summary-design.md §1.1).
     * Never queried or parsed server-side; only stored and echoed back as Base64.
     * AAD RULING 2b: recordId = accountId (row-per-account table, RULING 2b).
     * Crypto-shredded to NULL by {@link PregnancyProfileRepository#shredCiphersByUserId} at T0.
     */
    @Column(name = "hospital_admission_date_cipher", columnDefinition = "bytea")
    private byte[] hospitalAdmissionDateCipher;

    /**
     * Hospital discharge date — same posture as {@link #hospitalAdmissionDateCipher}.
     * Nullable; crypto-shredded to NULL at T0 alongside hospitalAdmissionDateCipher.
     */
    @Column(name = "hospital_discharge_date_cipher", columnDefinition = "bytea")
    private byte[] hospitalDischargeDateCipher;

    /**
     * Free-value delivery type (e.g. "vaginal", "cesarean"). Nullable; set at birth event.
     * Stored verbatim, never parsed.
     */
    @Column(name = "delivery_type", length = 64)
    private String deliveryType;

    /**
     * Free-text birth note. Language-agnostic; stored verbatim; never parsed.
     * Nullable; set at birth event.
     */
    @Column(name = "birth_note")
    private String birthNote;

    /**
     * Optimistic-concurrency token (data-model §2 {@code <sync>}; api-contract B2).
     * Server-assigned, monotonic, starts at 0. Sent to the client as the {@code If-Match}
     * value; mismatch on {@code PUT} → {@code 409}; header absent → {@code 428}.
     * JPA {@link Version} causes Hibernate to use this for dirty checking so it also
     * protects against concurrent JPA writes.
     */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Server-assigned, authoritative LWW clock for pull-replication ordering
     * (api-contract "Multi-device sync semantics"). Updated on every mutation.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete tombstone. Null = live record; non-null = deleted. Propagated via
     * {@code sync/pull} so the deletion appears on all devices.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
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

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public LocalDate getEdd() {
        return edd;
    }

    public void setEdd(LocalDate edd) {
        this.edd = edd;
    }

    public String getEddBasis() {
        return eddBasis;
    }

    public void setEddBasis(String eddBasis) {
        this.eddBasis = eddBasis;
    }

    public String getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(String lifecycle) {
        this.lifecycle = lifecycle;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public LocalDate getLossDate() {
        return lossDate;
    }

    public void setLossDate(LocalDate lossDate) {
        this.lossDate = lossDate;
    }

    public byte[] getMotherFirstNameCipher() {
        return motherFirstNameCipher;
    }

    public void setMotherFirstNameCipher(byte[] motherFirstNameCipher) {
        this.motherFirstNameCipher = motherFirstNameCipher;
    }

    public byte[] getMotherLastNameCipher() {
        return motherLastNameCipher;
    }

    public void setMotherLastNameCipher(byte[] motherLastNameCipher) {
        this.motherLastNameCipher = motherLastNameCipher;
    }

    public byte[] getBabyNameCipher() {
        return babyNameCipher;
    }

    public void setBabyNameCipher(byte[] babyNameCipher) {
        this.babyNameCipher = babyNameCipher;
    }

    public byte[] getHospitalAdmissionDateCipher() {
        return hospitalAdmissionDateCipher;
    }

    public void setHospitalAdmissionDateCipher(byte[] hospitalAdmissionDateCipher) {
        this.hospitalAdmissionDateCipher = hospitalAdmissionDateCipher;
    }

    public byte[] getHospitalDischargeDateCipher() {
        return hospitalDischargeDateCipher;
    }

    public void setHospitalDischargeDateCipher(byte[] hospitalDischargeDateCipher) {
        this.hospitalDischargeDateCipher = hospitalDischargeDateCipher;
    }

    public String getDeliveryType() {
        return deliveryType;
    }

    public void setDeliveryType(String deliveryType) {
        this.deliveryType = deliveryType;
    }

    public String getBirthNote() {
        return birthNote;
    }

    public void setBirthNote(String birthNote) {
        this.birthNote = birthNote;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
