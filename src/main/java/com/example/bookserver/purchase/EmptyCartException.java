package com.example.bookserver.purchase;

/** Tried to place an order from an empty cart. */
public class EmptyCartException extends RuntimeException {

    public EmptyCartException() {
        super("Cannot place an order from an empty cart");
    }
}
