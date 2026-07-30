package com.example.bookserver.user.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import com.example.bookserver.user.User;

/** Profile view returned to the client. Deliberately omits the password hash. */
public record UserProfileResponse(
        UUID userUuid,
        String userId,
        String userName,
        String phone,
        LocalDate birthDate,
        LocalDateTime createdAt) {

    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getUserUuid(),
                user.getUserId(),
                user.getUserName(),
                user.getPhone(),
                user.getBirthDate(),
                user.getCreatedAt());
    }
}
