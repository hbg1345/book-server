package com.example.bookserver.book.dto;

import java.util.List;

import com.example.bookserver.book.BookPage;

/**
 * A page of book results as the client sees it. The counts are what page controls are built
 * from; the collection endpoint currently returns one capped search page.
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
