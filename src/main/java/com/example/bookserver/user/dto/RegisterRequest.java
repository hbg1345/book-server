package com.example.bookserver.user.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body for POST /api/users (register). */
public record RegisterRequest(
        @NotBlank @Size(max = 100) String userId,
        @NotBlank String password,
        @NotBlank @Size(max = 100) String userName,
        @NotBlank @Pattern(regexp = "\\d{2,3}-\\d{3,4}-\\d{4}", message = "must match the format 010-1234-5678") String phone,
        @NotNull @Past LocalDate birthDate) {
}
