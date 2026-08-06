package com.example.bookserver.purchase;

/**
 * Thrown when the quantity to cancel is not one the line can give up — zero, negative, or more
 * copies than the order still holds.
 */
public class InvalidCancellationException extends RuntimeException {

    public InvalidCancellationException(String message) {
        super(message);
    }
}
