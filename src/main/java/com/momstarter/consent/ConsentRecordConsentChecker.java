package com.momstarter.consent;

import com.momstarter.pregnancy.ConsentChecker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Production {@link ConsentChecker} backed by {@link ConsentRecordRepository}.
 *
 * <h3>Gating strategy (per consent-slice-design.md §4.4)</h3>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(momstarter.consent.enforce=true)} — this bean is
 *       INACTIVE by default (property absent/false). Production, UAT, and the {@code test}
 *       profile all keep {@link com.momstarter.pregnancy.AlwaysGrantedConsentChecker} as the
 *       default UNTIL the flip. It activates ONLY when the property is explicitly {@code true}
 *       (set in prod config for the Phase-2 flip, and in ConsentCheckerIntegrationTest).</li>
 *   <li>{@code @Primary} — when active (property=true), this bean takes precedence over
 *       {@code AlwaysGrantedConsentChecker}.</li>
 * </ul>
 *
 * <h3>Phase 2 gate</h3>
 * The real checker is built and wired here but the actual "flip to production" (activating
 * this bean in the deployed environment) is a SEPARATE Phase-2 step, gated on the mobile
 * consent flow being released to users.  Until that flip, the deployed application continues
 * to use {@code AlwaysGrantedConsentChecker} in its default/UAT configuration.
 *
 * <h3>Fail-closed semantics (PDPA ม.26 — CRITICAL)</h3>
 * <ul>
 *   <li>No row → {@code false} (user has never consented → gate closed).</li>
 *   <li>Latest row {@code granted=false} → {@code false} (withdrawal active → gate closed).</li>
 *   <li>Query exception → {@code false} (DB blip → gate closed; metric emitted for alerting).</li>
 *   <li>Latest row {@code granted=true} → {@code true} (consent active → gate open).</li>
 * </ul>
 * Fail-open is NEVER acceptable here: this gate protects sensitive health data (PDPA ม.26).
 *
 * <h3>Monitoring (§4.2.1)</h3>
 * On any exception, {@code consent.checker.error} counter is incremented with a
 * {@code consent_type} tag.  Alert on rate {@literal >} 5/min (2 min sustained) to detect
 * DB blips causing a silent consent outage (burst 403s).
 *
 * @see com.momstarter.pregnancy.AlwaysGrantedConsentChecker
 * @see ConsentRecordRepository#findLatestGranted
 */
@Component
@Primary
@ConditionalOnProperty(name = "momstarter.consent.enforce", havingValue = "true")
public class ConsentRecordConsentChecker implements ConsentChecker {

    private static final Logger log = LoggerFactory.getLogger(ConsentRecordConsentChecker.class);

    private static final String METRIC_CONSENT_ERROR = "consent.checker.error";
    private static final String TAG_CONSENT_TYPE = "consent_type";

    private final ConsentRecordRepository consentRecordRepository;
    private final MeterRegistry meterRegistry;

    public ConsentRecordConsentChecker(ConsentRecordRepository consentRecordRepository,
                                       MeterRegistry meterRegistry) {
        this.consentRecordRepository = consentRecordRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes the hot-path native query:
     * {@code SELECT granted FROM consent_record WHERE user_id = ? AND consent_type = ?
     * ORDER BY granted_at DESC, granted ASC, id DESC LIMIT 1}
     *
     * <p>Returns {@code false} (fail-closed) if no row exists, the latest row is a
     * withdrawal, or any exception occurs during the query.
     *
     * @param userId      the authenticated user id
     * @param consentType one of the 6 PDPA consent-type strings
     * @return {@code true} only when the latest row has {@code granted=true}
     */
    @Override
    public boolean isGranted(UUID userId, String consentType) {
        try {
            return consentRecordRepository
                    .findLatestGranted(userId, consentType)
                    .orElse(false);  // no row → false (fail-closed: user never consented)
        } catch (Exception ex) {
            // DB blip or any unexpected error → fail-closed (gate stays shut).
            // Log with warn (not error) so on-call can correlate with consent.checker.error metric.
            // NEVER expose ex.getMessage() in any user-visible response.
            log.warn("consent check error user={} type={}", userId, consentType, ex);
            meterRegistry.counter(METRIC_CONSENT_ERROR, TAG_CONSENT_TYPE, consentType).increment();
            return false;  // fail-closed: doubt means deny (PDPA ม.26)
        }
    }
}
