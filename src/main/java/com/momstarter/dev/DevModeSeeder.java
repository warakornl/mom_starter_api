package com.momstarter.dev;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a ready-to-use test account when the dev mode flag is active.
 *
 * <p>This bean is created ONLY when {@code momstarter.dev.auto-verify-email=true}
 * (i.e. local profile) AND the active profile is not {@code prod} (via
 * {@code @Profile("!prod")} — belt-and-suspenders, see
 * deploy-pipeline-and-cloud-options.md §1.2 Path A). At startup it idempotently ensures
 * {@value #DEV_EMAIL} exists in the database with {@code email_verified=true} so testers can
 * log in immediately without going through registration or email verification.
 *
 * <p>Security notes:
 * <ul>
 *   <li>Never active in production ({@code @Profile("!prod")} blocks bean creation outright;
 *       {@link DevModeGuard} additionally refuses startup if misconfigured).</li>
 *   <li>Credentials are test-only and publicly documented; treat them as such.</li>
 *   <li>The password is encoded with the same Argon2id encoder as production passwords.</li>
 *   <li>A WARN log at startup makes the test credentials visible in the console.</li>
 * </ul>
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "momstarter.dev.auto-verify-email", havingValue = "true")
public class DevModeSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevModeSeeder.class);

    /** Email of the pre-seeded test account. */
    public static final String DEV_EMAIL = "dev@momstarter.local";

    /**
     * Raw (plain-text) password for the test account.
     * This is public test-only data — not a production secret.
     */
    public static final String DEV_PASSWORD_RAW = "DevTest-Password-2026";

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public DevModeSeeder(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!users.existsByEmail(DEV_EMAIL)) {
            User u = new User();
            u.setEmail(DEV_EMAIL);
            u.setPasswordHash(encoder.encode(DEV_PASSWORD_RAW));
            u.setEmailVerified(true);
            u.setLocale("th");
            users.save(u);
            log.warn("DEV SEED: created test account [{} / {}]", DEV_EMAIL, DEV_PASSWORD_RAW);
        }

        // Always log the warning so the tester sees credentials in the console
        log.warn("""
                ╔═══════════════════════════════════════════════════════════════════════╗
                ║  DEV MODE ON — auto-verify enabled, ready-to-use test account:       ║
                ║  Email    : dev@momstarter.local                                     ║
                ║  Password : DevTest-Password-2026                                    ║
                ║  This account is LOCAL ONLY. DO NOT use in production.               ║
                ╚═══════════════════════════════════════════════════════════════════════╝""");
    }
}
