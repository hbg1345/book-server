package com.example.bookserver.auth;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.bookserver.auth.dto.TokenResponse;
import com.example.bookserver.user.UserService;

/**
 * Authentication orchestration: verify credentials / rotate refresh tokens and mint
 * the resulting access + refresh pair.
 */
@Service
public class AuthService {

    private final UserService userService;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserService userService, JwtProvider jwtProvider,
                       RefreshTokenService refreshTokenService) {
        this.userService = userService;
        this.jwtProvider = jwtProvider;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Verify credentials and issue a fresh token pair.
     *
     * @throws com.example.bookserver.user.InvalidCredentialsException if id/password is wrong
     */
    public TokenResponse login(String userId, String rawPassword) {
        UUID userUuid = userService.login(userId, rawPassword);
        return issueFor(userUuid, refreshTokenService.issue(userUuid));
    }

    /**
     * Rotate a refresh token and issue a new pair.
     *
     * @throws InvalidRefreshTokenException if the refresh token is invalid/expired/replayed
     */
    public TokenResponse refresh(String refreshToken) {
        RotationResult rotated = refreshTokenService.rotate(refreshToken);
        return issueFor(rotated.userUuid(), rotated.refreshToken());
    }

    /** Revoke the refresh token's family so it can no longer be rotated. */
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private TokenResponse issueFor(UUID userUuid, String refreshToken) {
        return new TokenResponse(jwtProvider.issueAccessToken(userUuid), refreshToken);
    }
}
