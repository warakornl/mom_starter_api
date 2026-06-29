package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.dto.RegisterRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Two-phase, strictly non-enumerating registration (§E/§G). Register mints NO session and its
 * outcome is identical whether the email is new or already taken: a new email creates an
 * unverified account and emails a verification link; a colliding email creates/overwrites
 * nothing and instead emails the existing owner a notice. Password validation runs first and
 * may safely return a specific 422 (it describes the submitted password, not account existence).
 */
@Service
public class RegistrationService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final EmailVerificationService emailVerification;
    private final VerificationEmailSender emailSender;

    public RegistrationService(UserRepository users,
                               PasswordEncoder encoder,
                               PasswordPolicy passwordPolicy,
                               EmailVerificationService emailVerification,
                               VerificationEmailSender emailSender) {
        this.users = users;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.emailVerification = emailVerification;
        this.emailSender = emailSender;
    }

    public void register(RegisterRequest req) {
        passwordPolicy.validate(req.password());

        String email = normaliseEmail(req.email());
        Optional<User> existing = users.findByEmail(email);
        if (existing.isEmpty()) {
            User user = new User();
            user.setEmail(email);
            user.setPasswordHash(encoder.encode(req.password()));
            user.setLocale(req.locale());
            user.setEmailVerified(false);
            users.save(user);
            emailSender.sendVerification(email, emailVerification.issue(user));
        } else {
            emailSender.sendAlreadyRegisteredNotice(email);
        }
        // no session is minted; the caller returns an identical 202 either way
    }

    static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
