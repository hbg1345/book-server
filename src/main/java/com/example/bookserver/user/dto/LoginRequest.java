package com.example.bookserver.user.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for POST /api/auth/login. */
public record LoginRequest(
        @NotBlank String userId,
        @NotBlank String password) {
}
