package com.momstarter.dev;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DevFlags} is the single binding-layer holder for every {@code momstarter.dev.*} flag.
 * It carries {@code @Profile("!prod")}, so under the {@code prod} profile the bean itself does
 * not exist in the context — there is no way to construct a {@link DevFlags} instance whose
 * {@code isAutoVerifyEmail()} returns {@code true} while the active profile is {@code prod}.
 *
 * <p>These are plain unit tests of the POJO's own behaviour (mirrors the raw property value it
 * was constructed with). The profile-exclusion property itself is proven by
 * {@code com.momstarter.prodgate.ProdProfileRejectsDevBeansTest} (bean absent under prod) and by
 * {@code RegistrationServiceProdSafetyTest} (consumer falls back to {@code false} when the bean
 * is absent).
 */
class DevFlagsTest {

    @Test
    void autoVerifyEmail_reflectsConstructedValue_whenTrue() {
        DevFlags flags = new DevFlags(true);

        assertThat(flags.isAutoVerifyEmail()).isTrue();
    }

    @Test
    void autoVerifyEmail_reflectsConstructedValue_whenFalse() {
        DevFlags flags = new DevFlags(false);

        assertThat(flags.isAutoVerifyEmail()).isFalse();
    }

    @Test
    void classCarriesProfileNotProd() {
        org.springframework.context.annotation.Profile profile =
                DevFlags.class.getAnnotation(org.springframework.context.annotation.Profile.class);

        assertThat(profile)
                .as("DevFlags must carry @Profile(\"!prod\") so the bean is absent under prod, "
                        + "forcing every consumer's Optional<DevFlags> to be empty regardless of "
                        + "what momstarter.dev.auto-verify-email is set to")
                .isNotNull();
        assertThat(profile.value()).containsExactly("!prod");
    }
}
