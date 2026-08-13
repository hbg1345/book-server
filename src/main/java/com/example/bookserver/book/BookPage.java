package com.example.bookserver.book;

import java.util.List;
import java.util.stream.IntStream;

/**
 * One search page plus a bounded view of the pages immediately after it.
 *
 * @param navigationElements matches found from this page forward, capped at 101; it is not the
 *                           total number of search results
 */
public record BookPage(List<Book> content, int page, int size, int navigationElements) {

    /** The current page plus the four forward page buttons requested by the client UI. */
    public static final int NAVIGATION_PAGE_COUNT = 5;

    /** Existing page indexes from the current page through at most the next four pages. */
    public List<Integer> visiblePages() {
        int count = Math.min(
                NAVIGATION_PAGE_COUNT,
                (navigationElements + size - 1) / size);
        return IntStream.range(page, page + count).boxed().toList();
    }

    /** The page reached by {@code >>}, or {@code null} when no next block exists. */
    public Integer nextBlockPage() {
        return navigationElements > size * NAVIGATION_PAGE_COUNT
                ? page + NAVIGATION_PAGE_COUNT
                : null;
    }
}
