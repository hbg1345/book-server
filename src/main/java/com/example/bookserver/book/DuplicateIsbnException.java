package com.example.bookserver.book;

/**
 * A book with this ISBN is already in the catalogue.
 *
 * <p>Usually a resubmitted form rather than a mistake: the catalogue is the same book twice
 * otherwise, with the shop's stock of it split across two entries that neither the till nor the
 * shopper can tell apart.
 */
public class DuplicateIsbnException extends RuntimeException {

    public DuplicateIsbnException(String isbn) {
        super("A book with ISBN " + isbn + " already exists");
    }
}
