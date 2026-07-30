package com.example.bookserver.user.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for PUT /api/users/me/password. */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank String newPassword) {
}
