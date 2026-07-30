package com.example.bookserver.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for POST /api/auth/refresh. */
public record RefreshRequest(@NotBlank String refreshToken) {
}
