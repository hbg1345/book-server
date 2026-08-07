package com.example.bookserver.book.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body for updating a book (PUT /api/books/{uuid}). The catalogue entry only —
 * {@code inventory} is deliberately absent.
 *
 * <p>It used to be here, shared with {@link BookRequest}, and could not be omitted. That left
 * an editor no way to correct a title without also restating a stock figure, and the only
 * figure it had was the one it read before the edit began — so saving a typo fix put back
 * whatever had sold in the meantime. Stock moves through
 * {@code POST /api/books/{uuid}/stock} instead, which asks for a change rather than a total
 * and so cannot carry a stale one.
 */
public record UpdateBookRequest(
        @NotBlank @Size(max = 255) String bookTitle,
        @Size(max = 10_000) String bookDescription,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal price,
        @NotNull LocalDate publishDate,
        @NotBlank @Size(max = 100) String publisher,
        List<@NotNull UUID> authorUuids) {
}
