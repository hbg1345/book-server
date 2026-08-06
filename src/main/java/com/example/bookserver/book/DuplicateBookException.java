package com.example.bookserver.book;

/**
 * Thrown when a book would share its identity — title, publisher and publish date — with one
 * already in the catalogue. Usually a submitted-twice registration rather than a real attempt to
 * list the same book again, and the report is what tells the two apart: the caller learns the
 * book is already there instead of quietly creating a second row with its own stock.
 */
public class DuplicateBookException extends RuntimeException {

    public DuplicateBookException(String bookTitle) {
        super("book already in the catalogue: " + bookTitle);
    }
}
