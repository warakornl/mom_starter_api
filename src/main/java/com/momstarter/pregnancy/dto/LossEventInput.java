package com.momstarter.pregnancy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /pregnancy-profile/loss-event}
 * (functional-spec pregnancy-loss-recording-functional-spec.md §7.2 / api-contract L720).
 *
 * <p>{@code lossDate} is the ONLY field. It is OPTIONAL / default-empty / skippable
 * (LOSS-INV-11 / AC-1.7 / S6) — {@code {}} or no body at all is a fully valid request.
 *
 * <p><strong>Deliberately kept as a raw {@code String}</strong> (not {@code LocalDate} with
 * {@code @JsonFormat}) so the service layer can distinguish and classify every malformed shape
 * itself: a time-component-bearing string ({@code "2026-06-30T12:00:00"}), an impossible date,
 * or free text ALL map to {@code 422 validation_error details:"loss_date_malformed"}
 * (functional-spec §7.2/§10.12) rather than Jackson silently producing a generic Bean-Validation
 * {@code 422} with no {@code details} sub-code. A structurally non-JSON body (or a body that is
 * not a JSON object at all) never reaches this DTO — it is caught by
 * {@code GlobalExceptionHandler}'s {@code HttpMessageNotReadableException} handler and mapped to
 * {@code 400 bad_request} BEFORE Jackson attempts to bind fields (functional-spec §10.12 split).
 *
 * <p>Any unknown/extra body fields (e.g. a client sending {@code cause}/{@code type}/{@code note})
 * are silently ignored, never persisted, never rejected (NG-1) — the default Jackson
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} posture already used by every sibling profile DTO in
 * this module covers this without extra configuration here.
 */
public class LossEventInput {

    private String lossDate;

    /** Default constructor required by Jackson. */
    public LossEventInput() {}

    /**
     * Raw {@code lossDate} string exactly as submitted, or {@code null} when the key is
     * absent or explicitly JSON {@code null}. The service performs all date parsing/bounds
     * validation (functional-spec §7.2).
     */
    public String getLossDate() {
        return lossDate;
    }

    @JsonProperty("lossDate")
    public void setLossDate(String lossDate) {
        this.lossDate = lossDate;
    }
}
