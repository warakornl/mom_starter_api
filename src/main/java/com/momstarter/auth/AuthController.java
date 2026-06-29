package com.momstarter.auth;

import com.momstarter.auth.dto.AuthTokens;
import com.momstarter.auth.dto.DeviceSession;
import com.momstarter.auth.dto.LoginRequest;
import com.momstarter.auth.dto.LogoutRequest;
import com.momstarter.auth.dto.RefreshRequest;
import com.momstarter.auth.dto.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final RegistrationService registrationService;

    public AuthController(AuthService authService, RegistrationService registrationService) {
        this.authService = authService;
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        registrationService.register(request);
        return ResponseEntity.accepted().body(Map.of("code", "verification_pending"));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokens> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.login(request, httpRequest.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthTokens> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        authService.logout(request, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<DeviceSession>> sessions(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(authService.listSessions(UUID.fromString(jwt.getSubject())));
    }

    @DeleteMapping("/sessions/{deviceId}")
    public ResponseEntity<Void> revokeSession(@PathVariable String deviceId,
                                              @AuthenticationPrincipal Jwt jwt) {
        authService.revokeDevice(UUID.fromString(jwt.getSubject()), deviceId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sessions")
    public ResponseEntity<Void> revokeAllSessions(@AuthenticationPrincipal Jwt jwt) {
        authService.logoutAllDevices(UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }
}
