package com.example.bookserver.book;

/**
 * Thrown for a page request the catalogue will not serve: a negative page, a page size outside
 * the allowed range, or a page deeper than {@link BookService#MAX_PAGE}.
 *
 * <p>The depth limit is not arbitrary. OFFSET reads and discards every row ahead of the window,
 * so page 5000 costs a hundred thousand rows to answer — and nobody browsing a shop goes there.
 * Real catalogues cap the same way; the requests that reach that depth are crawlers.
 */
public class InvalidPageException extends RuntimeException {

    public InvalidPageException(String message) {
        super(message);
    }
}
