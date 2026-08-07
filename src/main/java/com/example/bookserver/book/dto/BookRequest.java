package com.example.bookserver.book.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body for creating a book (POST /api/books). {@code authorUuids} is optional and references
 * authors that must already exist.
 *
 * <p>{@code isbn} is the book's identity in the outside world, and the reason this endpoint can
 * refuse a duplicate: every call mints a fresh book_uuid, so without it a double-submitted form
 * left two identical books in the catalogue, each with its own stock. Editing does not carry it
 * — see {@link UpdateBookRequest} — because changing a book's ISBN does not correct the entry,
 * it names a different book.
 */
public record BookRequest(
        @NotBlank @Isbn13 String isbn,
        @NotBlank @Size(max = 255) String bookTitle,
        @Size(max = 10_000) String bookDescription,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal price,
        @NotNull LocalDate publishDate,
        @NotBlank @Size(max = 100) String publisher,
        @NotNull @Min(0) Integer inventory,
        List<@NotNull UUID> authorUuids) {
}
