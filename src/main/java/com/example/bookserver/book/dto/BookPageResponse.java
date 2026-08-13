package com.example.bookserver.book.dto;

import java.util.List;

import com.example.bookserver.book.BookPage;

/**
 * A page of search results and the bounded navigation controls that are known to exist without
 * counting every match. Page indexes are zero-based; the UI displays each value plus one.
 */
public record BookPageResponse(
        List<BookResponse> content,
        int page,
        int size,
        List<Integer> visiblePages,
        Integer nextBlockPage) {

    public static BookPageResponse from(BookPage page) {
        return new BookPageResponse(
                page.content().stream().map(BookResponse::from).toList(),
                page.page(),
                page.size(),
                page.visiblePages(),
                page.nextBlockPage());
    }
}
