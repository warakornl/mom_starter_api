package com.momstarter.auth;

import com.momstarter.error.ApiException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Egress precondition (§G/C9): a session whose JWT carries {@code email_verified=false} may
 * manage the account but must NOT push/pull/export/report health data. Call this at the start
 * of every cloud-egress handler (sync push/pull, reports, export), evaluated BEFORE the
 * {@code cloud_storage} consent gate. No such egress endpoints exist in this slice yet — this
 * guard is unit-tested and wired in when they land.
 */
@Component
public class EmailVerifiedGuard {

    public void requireVerified(Jwt jwt) {
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new ApiException(403, "email_unverified");
        }
    }
}
