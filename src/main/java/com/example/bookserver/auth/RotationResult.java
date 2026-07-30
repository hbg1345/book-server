package com.example.bookserver.auth;

import java.util.UUID;

/** Outcome of rotating a refresh token: the owner and the freshly issued token. */
public record RotationResult(UUID userUuid, String refreshToken) {
}
