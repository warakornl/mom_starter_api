package com.momstarter.pregnancy.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.momstarter.pregnancy.GestationalAge;
import com.momstarter.pregnancy.PostpartumAge;
import com.momstarter.pregnancy.PregnancyProfile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

/**
 * Response body for GET/PUT /pregnancy-profile and POST /pregnancy-profile/birth-event.
 *
 * <p>Supports <strong>both lifecycle modes</strong>:
 *
 * <p><strong>Pregnant ({@code lifecycle = "pregnant"})</strong> — gestational fields
 * ({@code gestationalWeek}, {@code gestationalDay}, {@code daysRemaining}, {@code progress})
 * are non-null; postpartum fields ({@code birthDate}, {@code postpartumDays},
 * {@code postpartumWeek}, {@code postpartumDay}) are {@code null} and therefore excluded from
 * the JSON by {@code @JsonInclude(NON_NULL)}.
 *
 * <p><strong>Postpartum ({@code lifecycle = "postpartum"})</strong> — gestational fields are
 * {@code null} (excluded from JSON); {@code birthDate} and postpartum fields are non-null.
 * {@code currentStage = "postpartum"} (fixed); {@code deliveryWindowActive = false} (always).
 *
 * <p>Derived fields are computed at request time from {@code edd} + client civil "today"
 * ({@code X-Client-Date} or server UTC fallback); they are never stored in the DB
 * (data-model §4 / §3.1). The client overrides the server snapshot with its own on-device
 * computation (OQ-2 — advisory only).
 *
 * <p>{@code deletedAt} is excluded from the JSON output when null ({@code NON_NULL}),
 * consistent with the &lt;sync&gt; "nullable tombstone" pattern.
 *
 * <h2>Name cipher fields</h2>
 * <p>{@code motherFirstName}, {@code motherLastName}, {@code babyName} are the Base64-encoded
 * wire representations of the {@code *_cipher bytea} columns (client-encrypted; the client
 * decrypts them using its DEK — Option A, name-fields-design.md Decision 2). They are
 * {@code null} when the column is NULL and excluded from JSON by {@code @JsonInclude(NON_NULL)}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PregnancyProfileResponse(
        // ---- <sync> block fields ----
        UUID id,
        UUID userId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,

        // ---- Stored fields ----
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate edd,
        String eddBasis,
        String lifecycle,

        /**
         * Floating-civil birth date (null while pregnant; set when postpartum).
         * OQ-11: civil date, zoneless YYYY-MM-DD — same FLAG-1 rule as edd.
         */
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate birthDate,

        /**
         * Floating-civil loss date (data-model §5 "Pregnancy-loss lifecycle transition" L275).
         * {@code null} unless {@code lifecycle = "ended"} and the mother chose to record a date
         * (OPTIONAL/skippable, LOSS-INV-11) — excluded from JSON by {@code @JsonInclude(NON_NULL)}.
         * Set by {@code POST /pregnancy-profile/loss-event}; cleared to {@code null} in the same
         * transaction as {@code POST /pregnancy-profile/reopen} (S4/LOSS-INV-6).
         */
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate lossDate,

        // ---- Name cipher fields (client-encrypted Base64, optional/nullable) ----

        /**
         * Mother's first name — Base64 ciphertext (client-encrypted, Option A).
         * {@code null} when column is NULL; excluded from JSON by {@code NON_NULL}.
         */
        String motherFirstName,

        /**
         * Mother's last name — same as {@link #motherFirstName}.
         */
        String motherLastName,

        /**
         * Baby's name — same as {@link #motherFirstName}. Capturable anytime (pre/post birth).
         */
        String babyName,

        // ---- Hospital-stay cipher fields (client-encrypted Base64, optional/nullable) ----

        /**
         * Hospital admission date — Base64 ciphertext (client-encrypted, Option A,
         * pregnancy-summary-design.md §1.2). {@code null} when column is NULL;
         * excluded from JSON by {@code NON_NULL}. Set via POST /birth-event.
         * The server never parses or validates this date; temporal logic is client-side.
         */
        String hospitalAdmissionDate,

        /**
         * Hospital discharge date — same posture as {@link #hospitalAdmissionDate}.
         * {@code null} when column is NULL; excluded from JSON by {@code NON_NULL}.
         */
        String hospitalDischargeDate,

        // ---- Derived gestational-age snapshot (null when postpartum) ----

        /**
         * Completed gestational weeks. {@code null} when {@code lifecycle = "postpartum"}.
         * Using {@link Integer} (not {@code int}) so {@code @JsonInclude(NON_NULL)} can
         * exclude this field from the postpartum response body.
         */
        Integer gestationalWeek,

        /**
         * Euclidean day-in-week (0..6). {@code null} when postpartum.
         */
        Integer gestationalDay,

        /**
         * Days until EDD (negative once past EDD). {@code null} when postpartum.
         */
        Long daysRemaining,

        /**
         * Progress ring value 0..1 (real division). {@code null} when postpartum.
         */
        Double progress,

        /**
         * Current stage string. {@code "T1"/"T2"/"T3"} while pregnant;
         * {@code "postpartum"} (fixed) when postpartum.
         */
        String currentStage,

        /**
         * Delivery-window overlay. {@code true} when {@code lifecycle = pregnant} AND
         * {@code gestationalWeek >= 37}; always {@code false} when postpartum.
         * Kept as primitive {@code boolean} (never null) per contract — postpartum returns
         * {@code false}, not absent.
         */
        boolean deliveryWindowActive,

        // ---- Derived postpartum snapshot (null when pregnant) ----

        /**
         * {@code max(0, civilDaysBetween(birthDate, today))}. {@code null} when pregnant.
         */
        Integer postpartumDays,

        /**
         * {@code floorDiv(postpartumDays, 7)} — completed weeks since birth (week 0 = first week).
         * {@code null} when pregnant.
         */
        Integer postpartumWeek,

        /**
         * {@code floorMod(postpartumDays, 7)} — 0..6 Euclidean day-in-week.
         * {@code null} when pregnant.
         */
        Integer postpartumDay) {

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Builds a <strong>pregnant-lifecycle</strong> response from the stored profile and the
     * pre-computed {@link GestationalAge} snapshot.
     *
     * <p>Postpartum fields ({@code birthDate}, {@code postpartumDays}, {@code postpartumWeek},
     * {@code postpartumDay}) are set to {@code null} and excluded from the JSON output
     * by {@code @JsonInclude(NON_NULL)}.
     */
    public static PregnancyProfileResponse of(PregnancyProfile p, GestationalAge ga) {
        return new PregnancyProfileResponse(
                p.getId(),
                p.getUserId(),
                p.getVersion(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getDeletedAt(),
                p.getEdd(),
                p.getEddBasis(),
                p.getLifecycle(),
                p.getBirthDate(),             // null while pregnant
                p.getLossDate(),              // null unless ended + date recorded
                toBase64(p.getMotherFirstNameCipher()),
                toBase64(p.getMotherLastNameCipher()),
                toBase64(p.getBabyNameCipher()),
                toBase64(p.getHospitalAdmissionDateCipher()),
                toBase64(p.getHospitalDischargeDateCipher()),
                ga.gestationalWeek(),         // int auto-boxed to Integer
                ga.gestationalDay(),          // int auto-boxed to Integer
                ga.daysRemaining(),           // long auto-boxed to Long
                ga.progress(),               // double auto-boxed to Double
                ga.currentStage(),
                ga.deliveryWindowActive(),
                null,                        // postpartumDays — absent for pregnant
                null,                        // postpartumWeek — absent for pregnant
                null                         // postpartumDay  — absent for pregnant
        );
    }

    /**
     * Builds a <strong>postpartum-lifecycle</strong> response from the stored profile and the
     * pre-computed {@link PostpartumAge} snapshot.
     *
     * <p>Gestational fields ({@code gestationalWeek}, {@code gestationalDay},
     * {@code daysRemaining}, {@code progress}) are set to {@code null} and excluded from the
     * JSON output by {@code @JsonInclude(NON_NULL)}, per the api-contract:
     * "gestational fields = null once postpartum".
     *
     * <p>{@code currentStage} is fixed to {@code "postpartum"} and
     * {@code deliveryWindowActive} is fixed to {@code false} (the wk-37 overlay is
     * pregnant-only).
     */
    public static PregnancyProfileResponse of(PregnancyProfile p, PostpartumAge pa) {
        return new PregnancyProfileResponse(
                p.getId(),
                p.getUserId(),
                p.getVersion(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getDeletedAt(),
                p.getEdd(),
                p.getEddBasis(),
                p.getLifecycle(),
                p.getBirthDate(),             // non-null when postpartum
                p.getLossDate(),              // null (postpartum profile never records a loss)
                toBase64(p.getMotherFirstNameCipher()),
                toBase64(p.getMotherLastNameCipher()),
                toBase64(p.getBabyNameCipher()),
                toBase64(p.getHospitalAdmissionDateCipher()),
                toBase64(p.getHospitalDischargeDateCipher()),
                null,                        // gestationalWeek — absent for postpartum
                null,                        // gestationalDay  — absent for postpartum
                null,                        // daysRemaining   — absent for postpartum
                null,                        // progress        — absent for postpartum
                "postpartum",               // currentStage is FIXED when postpartum
                false,                       // deliveryWindowActive ALWAYS false (pregnant-only overlay)
                pa.postpartumDays(),         // int auto-boxed to Integer
                pa.postpartumWeek(),         // int auto-boxed to Integer
                pa.postpartumDay()           // int auto-boxed to Integer
        );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Encodes raw {@code bytea} bytes to Base64 for the wire response, or returns
     * {@code null} when the column is NULL (cipher not set / crypto-shredded).
     * {@code @JsonInclude(NON_NULL)} on the record then omits the field from JSON.
     */
    private static String toBase64(byte[] cipherBytes) {
        return cipherBytes == null ? null : Base64.getEncoder().encodeToString(cipherBytes);
    }
}
