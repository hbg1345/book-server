package com.example.bookserver.book;

import java.util.List;

/**
 * One page of a book result set, with what a client needs to render page controls: the books,
 * where this page sits, and how many results there are in total.
 *
 * @param totalElements every result, not the size of this page — it is what the page count is
 *                      derived from
 */
public record BookPage(List<Book> content, int page, int size, long totalElements) {

    /** Ceiling division: 21 books at 20 per page is 2 pages, not 1. */
    public int totalPages() {
        return (int) ((totalElements + size - 1) / size);
    }
}
