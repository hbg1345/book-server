package com.example.bookserver.purchase;

import java.util.UUID;

/** Thrown when an order action names a book the order does not contain (or no longer contains). */
public class OrderItemNotFoundException extends RuntimeException {

    public OrderItemNotFoundException(UUID purchaseUuid, UUID bookUuid) {
        super("order " + purchaseUuid + " has no line for book " + bookUuid);
    }
}
