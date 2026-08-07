package com.example.bookserver;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Valid, distinct ISBN-13s for tests.
 *
 * <p>{@code book.isbn} is NOT NULL and uniquely indexed, so every test that inserts a book needs
 * one, and two books in the same test need two. Writing them by hand means computing check
 * digits by hand, and a test that fails because its literal ISBN was mistyped tells you nothing
 * about the code.
 *
 * <p>The counter is static and never resets, so ids stay distinct across the whole run even
 * though {@code reset.sql} truncates between methods — a test that leaks a book into the next
 * one should fail on its own assertions, not on a spurious duplicate-key error.
 */
public final class Isbns {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private Isbns() {
    }

    /** A fresh ISBN-13 nobody else in this run has used. */
    public static String next() {
        return withBody("978" + String.format("%09d", SEQ.getAndIncrement()));
    }

    /** The check digit that makes {@code body} (978/979 + nine digits) a valid ISBN-13. */
    private static String withBody(String body) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (body.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return body + (10 - sum % 10) % 10;
    }
}
