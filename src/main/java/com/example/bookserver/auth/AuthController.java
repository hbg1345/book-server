package com.example.bookserver.auth;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.auth.dto.LogoutRequest;
import com.example.bookserver.auth.dto.RefreshRequest;
import com.example.bookserver.auth.dto.TokenResponse;
import com.example.bookserver.user.dto.LoginRequest;

import jakarta.validation.Valid;

/**
 * Authentication endpoints. Stateless: login/refresh return an access + refresh token
 * pair in the JSON body; the client sends the access token as {@code Authorization: Bearer}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req.userId(), req.password());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    public void logout(@Valid @RequestBody LogoutRequest req) {
        authService.logout(req.refreshToken());
    }
}
