package com.example.bookserver.book;

/**
 * Thrown when a catalogue search is asked for with nothing to search for. A blank
 * query matches every title, so serving it would return the whole catalogue under
 * a name that promises otherwise.
 */
public class BlankSearchQueryException extends RuntimeException {

    public BlankSearchQueryException() {
        super("search query must not be blank");
    }
}
