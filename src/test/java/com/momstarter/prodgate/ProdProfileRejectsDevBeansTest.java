package com.momstarter.prodgate;

import com.momstarter.MomStarterApiApplication;
import com.momstarter.auth.PasswordEmailSender;
import com.momstarter.auth.VerificationEmailSender;
import com.momstarter.dev.DevModeGuard;
import com.momstarter.dev.DevModeSeeder;
import com.momstarter.dev.LocalDevPasswordEmailSender;
import com.momstarter.dev.ResetTokenExposureGuard;
import com.momstarter.encryption.KmsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Launch-gate #5 (dev-mode HARD-OFF in prod) — build-time CI assertion.
 *
 * <p>Boots the REAL Spring Boot application ({@link MomStarterApiApplication}) with
 * {@code spring.profiles.active=prod}, proving via an actual running context (not a
 * regex/yaml read) that:
 *
 * <ul>
 *   <li><b>Assert 1 (negative case)</b>: with prod profile active and NO dev flags set
 *       (the real deploy default), none of the four dev-only beans exist in the context.</li>
 *   <li><b>Assert 2 (positive-poison case)</b>: with prod profile active AND a dev flag
 *       deliberately set to {@code true} (misconfiguration), the four dev-only beans STILL do
 *       not exist and the context boots cleanly — the poisoned flag is completely inert,
 *       regardless of what the datasource URL looks like. This closes the localhost-tunnel
 *       loophole that a {@code @ConditionalOnProperty}-only guard cannot close (see
 *       {@code deploy-pipeline-and-cloud-options.md} §1.2).</li>
 * </ul>
 *
 * <p><b>Design note — deviation from the pipeline-design doc's literal wording:</b> the design
 * doc (§1.2 Assert 2) says the poison case "must fail at startup always." Once
 * {@code @Profile("!prod")} is actually applied (as this pass does), that literal wording turns
 * out to be mechanically impossible to satisfy AND undesirable: {@code @Profile} excludes the
 * bean at bean-creation time, before its own {@code @PostConstruct} safety check would run —
 * so there is no longer any bean left in the context that COULD throw. The poisoned flag
 * becomes fully inert instead. This is a STRONGER security property than "crashes on
 * misconfiguration" (a crash still requires someone to notice and read the crash — inertness
 * means the flag simply has zero effect on a prod deploy, full stop). This test asserts the
 * correct, safer, actually-achievable outward observable (clean boot + beans absent) rather
 * than forcing an artificial crash to satisfy the doc's literal text. Flagged for
 * {@code infra-reviewer} to confirm/update the design doc wording in delta re-review.
 *
 * <p><b>Non-vacuousness:</b> Assert 1 only proves something because the four dev beans carry
 * {@code @Profile("!prod")} (bean-creation-level exclusion). If that annotation is removed,
 * this test FAILS (the bean would still exist because its {@code @ConditionalOnProperty}
 * flag is absent = false everywhere, which is a DIFFERENT, weaker reason for absence — see
 * the design doc's false-green analysis). Do not "fix" this test by asserting on flag state.
 *
 * <p><b>Bootability stub note (Pass 1 scope):</b> {@code AwsKmsClient} and a real SES sender do
 * not exist yet (Pass 2). Once Pass 2 firewalls {@code MockKmsClient}/{@code Logging*Sender}
 * with {@code @Profile("!prod")} (mirroring this pass's dev-bean treatment), a full
 * {@code prod} profile context will have NO {@link KmsClient} / {@link PasswordEmailSender} /
 * {@link VerificationEmailSender} bean at all. The {@link TestStubConfig} nested class below
 * supplies minimal TEST-ONLY {@code @Primary} stubs so this test boots a prod context both
 * today AND after Pass 2 lands, without depending on the incidental (soon-to-be-firewalled)
 * mock/logging beans. These stubs live ONLY in this test file — Pass 2 will add real
 * {@code @Profile("prod")} production beans (AwsKmsClient, SES senders) to src/main, at which
 * point this stub config becomes unnecessary and should be removed.
 */
class ProdProfileRejectsDevBeansTest {

    private ConfigurableApplicationContext context;

    /**
     * Base overrides applied as real JVM system properties (NOT
     * {@link SpringApplicationBuilder#properties}, which register as Boot "default properties" —
     * the LOWEST precedence source, so they cannot override a profile-specific YAML file such as
     * a future {@code application-prod.yml}). System properties sit above profile YAML in Boot's
     * property-source order, so they reliably override any datasource URL prod config declares.
     */
    private static Map<String, String> baseSystemProps(String schemaName) {
        return Map.of(
                "spring.datasource.url",
                "jdbc:h2:mem:" + schemaName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username", "sa",
                "spring.datasource.password", "",
                "spring.flyway.enabled", "false",
                // Random free port: SecurityConfig's SecurityFilterChain needs a real servlet
                // web application context (HttpSecurity is only available in one), but this
                // test has no need to actually bind to a fixed/well-known port.
                "server.port", "0"
        );
    }

    private static void setSystemProps(Map<String, String> props) {
        props.forEach(System::setProperty);
    }

    private static void clearSystemProps(Map<String, String> props) {
        props.keySet().forEach(System::clearProperty);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void prodProfileWithNoDevFlagsHasNoDevBeans() {
        Map<String, String> props = baseSystemProps("prodgate1");
        setSystemProps(props);
        try {
            context = new SpringApplicationBuilder(MomStarterApiApplication.class, TestStubConfig.class)
                    .web(WebApplicationType.SERVLET)
                    .profiles("prod")
                    .run();

            assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(DevModeGuard.class));
            assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(DevModeSeeder.class));
            assertThrows(NoSuchBeanDefinitionException.class,
                    () -> context.getBean(LocalDevPasswordEmailSender.class));
            assertThrows(NoSuchBeanDefinitionException.class,
                    () -> context.getBean(ResetTokenExposureGuard.class));
        } finally {
            clearSystemProps(props);
        }
    }

    @Test
    void prodProfileWithDevFlagsTruePoisonedIsInertAndBeansStayAbsent() {
        // Poison case: prod profile + dev flags deliberately true (simulating an operator
        // mistake) + a datasource URL that LOOKS local (h2 mem, which DevModeGuard.isLocalUrl()
        // would treat as "local"). This closes the exact localhost-tunnel loophole the design
        // doc (deploy-pipeline-and-cloud-options.md §1.2) flags: under @ConditionalOnProperty
        // ALONE, a "local-looking" datasource URL would make the flag-driven guards choose NOT
        // to throw, silently letting dev-mode behaviour run in what LOOKS like a safe context
        // but is actually prod.
        //
        // IMPORTANT — this assertion is INTENTIONALLY NOT "the context fails to start."
        // @Profile("!prod") excludes DevModeGuard/DevModeSeeder/LocalDevPasswordEmailSender/
        // ResetTokenExposureGuard at bean-creation time, which happens BEFORE any
        // @PostConstruct safety check would even run. Once the bean cannot be constructed at
        // all, there is nothing left in the context to throw — so a poisoned flag combination
        // now produces a CLEAN, SUCCESSFUL boot with the dev machinery completely absent and
        // completely inert, rather than a crash. This is objectively the stronger/safer outcome
        // (the flag has zero effect on a prod deploy, full stop) and is the correct outward
        // observable to assert. See ProdProfileRejectsDevBeansTest class javadoc "Design note"
        // for the full explanation of why this differs from the pipeline-design doc's literal
        // "must fail at startup" wording.
        Map<String, String> props = new java.util.HashMap<>(baseSystemProps("prodgate2"));
        props.put("momstarter.dev.auto-verify-email", "true");
        props.put("momstarter.dev.expose-reset-token", "true");
        setSystemProps(props);
        try {
            context = new SpringApplicationBuilder(MomStarterApiApplication.class, TestStubConfig.class)
                    .web(WebApplicationType.SERVLET)
                    .profiles("prod")
                    .run();

            assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(DevModeGuard.class));
            assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(DevModeSeeder.class));
            assertThrows(NoSuchBeanDefinitionException.class,
                    () -> context.getBean(LocalDevPasswordEmailSender.class));
            assertThrows(NoSuchBeanDefinitionException.class,
                    () -> context.getBean(ResetTokenExposureGuard.class));
        } finally {
            clearSystemProps(props);
        }
    }

    @Test
    void prodProfileGetsRealConsentCheckerWhenEnforced() {
        Map<String, String> props = new java.util.HashMap<>(baseSystemProps("prodgate3"));
        props.put("momstarter.consent.enforce", "true");
        setSystemProps(props);
        try {
            context = new SpringApplicationBuilder(MomStarterApiApplication.class, TestStubConfig.class)
                    .web(WebApplicationType.SERVLET)
                    .profiles("prod")
                    .run();

            Object consentChecker = context.getBean(com.momstarter.pregnancy.ConsentChecker.class);
            assertThat(consentChecker.getClass().getSimpleName())
                    .isEqualTo("ConsentRecordConsentChecker");
        } finally {
            clearSystemProps(props);
        }
    }

    /**
     * Deterministic, mechanical proof of non-vacuousness for
     * {@link #prodProfileWithNoDevFlagsHasNoDevBeans()}.
     *
     * <p>The context-boot test above CANNOT by itself distinguish "bean absent because
     * {@code @Profile(\"!prod\")} excluded it" from "bean absent because its
     * {@code @ConditionalOnProperty} flag happened to be unset" — both produce the same outward
     * symptom (bean missing). This test closes that gap directly: it asserts, via reflection,
     * that each of the four dev-only classes is ACTUALLY annotated {@code @Profile("!prod")}
     * (or an equivalent negated-prod expression). If someone removes {@code @Profile("!prod")}
     * from any of these classes, THIS test fails immediately and unconditionally — it does not
     * depend on flag state, datasource URL, or any other incidental runtime condition.
     *
     * <p>Combined with {@link #prodProfileWithNoDevFlagsHasNoDevBeans()} (which proves the
     * annotation actually has the intended effect on a real running context), the two tests
     * together prove Assert 1 is non-vacuous: the annotation is present AND it is the reason
     * the bean is absent under prod.
     */
    @Test
    void allFourDevBeansCarryProfileNotProdAnnotation() {
        List<Class<?>> devOnlyBeans = List.of(
                DevModeGuard.class,
                DevModeSeeder.class,
                LocalDevPasswordEmailSender.class,
                ResetTokenExposureGuard.class
        );

        for (Class<?> devBean : devOnlyBeans) {
            Profile profile = devBean.getAnnotation(Profile.class);
            assertThat(profile)
                    .as("%s must carry @Profile so it is excluded at bean-creation time under "
                            + "the prod profile, independent of any @ConditionalOnProperty flag "
                            + "state (see deploy-pipeline-and-cloud-options.md §1.2 Path A)",
                            devBean.getSimpleName())
                    .isNotNull();
            assertThat(profile.value())
                    .as("%s's @Profile value must exclude prod (expected \"!prod\")",
                            devBean.getSimpleName())
                    .containsExactly("!prod");
        }
    }

    /**
     * TEST-ONLY stub beans so a {@code prod}-profile context can boot in this Pass-1 test even
     * though {@code AwsKmsClient} and a real SES sender do not exist yet. Pass 2 introduces the
     * real {@code @Profile("prod")} production beans in {@code src/main}; this class must NOT be
     * copied into main source — it exists solely to make this CI gate test bootable today.
     *
     * <p>Beans are {@code @ConditionalOnMissingBean} (NOT {@code @Primary}) so they only fill in
     * when nothing else supplies the type. Today {@code MockKmsClient}/{@code Logging*Sender}
     * still supply these types unconditionally under prod (Pass 2 scope, untouched here), so
     * these stubs stay dormant today — they exist purely so this test keeps booting once Pass 2
     * firewalls those beans. Using {@code @Primary} here would collide with
     * {@code LocalDevPasswordEmailSender} in the positive-poison test (which sets
     * {@code expose-reset-token=true}, activating it via {@code @ConditionalOnProperty} BEFORE
     * its guard's {@code @PostConstruct} runs) and mask the real guard failure with an unrelated
     * "duplicate primary bean" error.
     */
    @TestConfiguration
    static class TestStubConfig {

        @Bean
        @ConditionalOnMissingBean(KmsClient.class)
        KmsClient testOnlyStubKmsClient() {
            return new KmsClient() {
                @Override
                public GeneratedDek generateDek(String accountId) {
                    throw new UnsupportedOperationException("test-only stub — Pass 2 provides AwsKmsClient");
                }

                @Override
                public byte[] decryptDek(byte[] wrappedDek, String accountId) {
                    throw new UnsupportedOperationException("test-only stub — Pass 2 provides AwsKmsClient");
                }

                @Override
                public RewrappedDek reEncryptDek(byte[] wrappedDek, String accountId) {
                    throw new UnsupportedOperationException("test-only stub — Pass 2 provides AwsKmsClient");
                }

                @Override
                public String currentKeyId() {
                    return "test-only-stub-key";
                }
            };
        }

        @Bean
        @ConditionalOnMissingBean(PasswordEmailSender.class)
        PasswordEmailSender testOnlyStubPasswordEmailSender() {
            return new PasswordEmailSender() {
                @Override
                public void sendPasswordReset(String email, String rawToken) {
                    // test-only no-op — Pass 2 provides the real SES-backed sender
                }

                @Override
                public void sendPasswordChangedNotice(String email) {
                    // test-only no-op — Pass 2 provides the real SES-backed sender
                }
            };
        }

        @Bean
        @ConditionalOnMissingBean(VerificationEmailSender.class)
        VerificationEmailSender testOnlyStubVerificationEmailSender() {
            return new VerificationEmailSender() {
                @Override
                public void sendVerification(String email, String rawToken) {
                    // test-only no-op — Pass 2 provides the real SES-backed sender
                }

                @Override
                public void sendAlreadyRegisteredNotice(String email) {
                    // test-only no-op — Pass 2 provides the real SES-backed sender
                }
            };
        }
    }
}
