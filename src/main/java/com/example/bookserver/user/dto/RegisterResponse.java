package com.example.bookserver.user.dto;

import java.util.UUID;

/** Response for a successful register: the newly generated user_uuid. */
public record RegisterResponse(UUID userUuid) {
}
