package com.example.bookserver.book.dto;

/**
 * What the book holds after a stock movement (POST /api/books/{uuid}/stock).
 *
 * <p>Returned because the caller sent a change and not a total, so it has no way to work the
 * result out for itself — and reading it back in a second request would only tell it what the
 * count was by then, which is a different question.
 */
public record StockResponse(int inventory) {
}
