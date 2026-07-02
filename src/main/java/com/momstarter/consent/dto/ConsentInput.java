package com.momstarter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /account/consents}.
 *
 * <p>The {@code locale} field is intentionally absent — the server normalises
 * the {@code Accept-Language} header (in {@code ConsentService}) to {@code 'th'}
 * or {@code 'en'} before INSERT.  Clients must NOT send a locale field; the server
 * is authoritative (PDPA ม.19(ข) audit evidence).
 *
 * <p>The {@code id} and {@code grantedAt} fields are intentionally absent —
 * both are server-assigned (UUID and {@code DEFAULT now()} respectively).
 */
public class ConsentInput {

    /**
     * One of the 6 PDPA consent-type strings:
     * {@code general_health}, {@code sensitive_lab_results}, {@code pdf_egress},
     * {@code infant_feeding}, {@code cloud_storage}, {@code child_health}.
     *
     * <p>Validated against the known set in {@code ConsentService#record}; an
     * unknown value returns {@code 422 validation_error}.
     */
    @NotBlank
    private String consentType;

    /**
     * {@code true} = the user is granting the consent;
     * {@code false} = the user is withdrawing it (ม.19(ค)).
     *
     * <p>Both directions produce a new immutable row (append-only model).
     */
    @NotNull
    private Boolean granted;

    /**
     * Version tag of the consent text that was displayed to the user
     * (e.g. {@code "v1.0-th"}, {@code "v2.1-en"}).
     *
     * <p>Stored verbatim as audit evidence that the user consented to the
     * correct, current text (PDPA ม.19(ข)).  The client is responsible for
     * supplying the version of the text it rendered.
     */
    @NotBlank
    private String consentTextVersion;

    public String getConsentType() {
        return consentType;
    }

    public void setConsentType(String consentType) {
        this.consentType = consentType;
    }

    public Boolean getGranted() {
        return granted;
    }

    public void setGranted(Boolean granted) {
        this.granted = granted;
    }

    public String getConsentTextVersion() {
        return consentTextVersion;
    }

    public void setConsentTextVersion(String consentTextVersion) {
        this.consentTextVersion = consentTextVersion;
    }
}
