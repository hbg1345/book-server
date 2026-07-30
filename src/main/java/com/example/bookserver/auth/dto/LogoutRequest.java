package com.example.bookserver.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for POST /api/auth/logout. */
public record LogoutRequest(@NotBlank String refreshToken) {
}
