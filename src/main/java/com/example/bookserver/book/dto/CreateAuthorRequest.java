package com.example.bookserver.book.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for POST /api/authors (create an author). */
public record CreateAuthorRequest(
        @NotBlank @Size(max = 100) String authorName) {
}
