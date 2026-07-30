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
 * Body for creating (POST /api/books) and updating (PUT /api/books/{uuid}) a book.
 * {@code authorUuids} is optional and references authors that must already exist.
 */
public record BookRequest(
        @NotBlank @Size(max = 255) String bookTitle,
        @Size(max = 10_000) String bookDescription,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal price,
        @NotNull LocalDate publishDate,
        @NotBlank @Size(max = 100) String publisher,
        @NotNull @Min(0) Integer inventory,
        List<@NotNull UUID> authorUuids) {
}
