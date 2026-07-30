package com.example.bookserver.book.dto;

import java.util.UUID;

/** Response for a successful create: the newly generated author_uuid. */
public record CreateAuthorResponse(UUID authorUuid) {
}
