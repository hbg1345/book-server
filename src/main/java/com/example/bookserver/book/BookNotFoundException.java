package com.example.bookserver.book;

import java.util.UUID;

/** Thrown when an operation targets a book_uuid that does not exist. */
public class BookNotFoundException extends RuntimeException {

    public BookNotFoundException(UUID bookUuid) {
        super("book not found: " + bookUuid);
    }
}
