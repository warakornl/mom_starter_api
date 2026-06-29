package com.momstarter.auth;

import com.momstarter.account.User;
import com.momstarter.account.UserRepository;
import com.momstarter.auth.dto.AuthTokens;
import com.momstarter.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Google sign-in (§J/G1–G6). Google is the identity provider but the app mints its OWN session
 * (the same opaque-rotating-refresh family + RS256 access token). A Google token never becomes
 * the app session; a federated sign-in is just another session-minting event.
 */
@Service
public class GoogleSignInService {

    private static final String PROVIDER = "google";

    private final GoogleIdTokenVerifier verifier;
    private final AuthIdentityRepository identities;
    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final JwtService jwt;

    public GoogleSignInService(GoogleIdTokenVerifier verifier,
                               AuthIdentityRepository identities,
                               UserRepository users,
                               RefreshTokenService refreshTokens,
                               JwtService jwt) {
        this.verifier = verifier;
        this.identities = identities;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
    }

    @Transactional
    public AuthTokens signIn(String idToken, String nonce, String deviceId) {
        GoogleIdentity google = verifier.verify(idToken, nonce); // 401 google_token_invalid on failure

        // belt-and-suspenders at the session-minting boundary: never mint for an unverified email,
        // even if a future verifier implementation regresses (the verifier owns this per G2).
        if (!google.emailVerified()) {
            throw new ApiException(401, "google_token_invalid");
        }

        User user = identities.findByProviderAndProviderSub(PROVIDER, google.sub())
                .map(link -> users.findById(link.getUserId())
                        .orElseThrow(() -> new ApiException(401, "google_token_invalid")))
                .orElseGet(() -> resolveNewOrCollision(google));

        RefreshTokenService.Issued issued = refreshTokens.mintFamily(user.getId(), deviceId, null);
        String accessToken = jwt.issueAccessToken(user.getId(), user.isEmailVerified());
        return new AuthTokens(accessToken, issued.rawToken(),
                jwt.accessTtlSeconds(), RefreshTokenService.REFRESH_TTL.toSeconds());
    }

    /** No existing google link: either a brand-new federated user, or a collision with an
     *  existing (password) account — which requires an explicit, proven link (no auto-merge). */
    private User resolveNewOrCollision(GoogleIdentity google) {
        String email = normaliseEmail(google.email());
        if (users.findByEmail(email).isPresent()) {
            throw new ApiException(409, "link_required");
        }
        User user = new User();
        user.setEmail(email);
        user.setEmailVerified(google.emailVerified()); // Google-verified email; no password (federated-only)
        users.save(user);

        AuthIdentity identity = new AuthIdentity();
        identity.setUserId(user.getId());
        identity.setProvider(PROVIDER);
        identity.setProviderSub(google.sub());
        identity.setEmail(email);
        identities.save(identity);
        return user;
    }

    static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
