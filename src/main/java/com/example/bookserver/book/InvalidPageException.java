package com.example.bookserver.book;

/** A negative search-result page has no meaning. */
public class InvalidPageException extends RuntimeException {

    public InvalidPageException(String message) {
        super(message);
    }
}
