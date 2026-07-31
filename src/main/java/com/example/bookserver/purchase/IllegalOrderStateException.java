package com.example.bookserver.purchase;

/** The requested transition is not allowed from the order's current state
 *  (e.g. paying an order that is not pending, or cancelling one already shipped). */
public class IllegalOrderStateException extends RuntimeException {

    public IllegalOrderStateException(String message) {
        super(message);
    }
}
