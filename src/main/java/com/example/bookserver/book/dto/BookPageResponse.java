package com.example.bookserver.book.dto;

import java.util.List;

import com.example.bookserver.book.BookPage;

/**
 * A page of books as the client sees it. The counts are what page controls are built from:
 * {@code totalElements} for "103,056 books", {@code totalPages} for the last page number.
 */
public record BookPageResponse(
        List<BookResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static BookPageResponse from(BookPage page) {
        return new BookPageResponse(
                page.content().stream().map(BookResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
