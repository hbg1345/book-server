package com.example.bookserver.book.dto;

import java.util.UUID;

/** Response for a successful create: the newly generated book_uuid. */
public record CreateBookResponse(UUID bookUuid) {
}
