package com.example.bookserver.purchase;

import java.util.UUID;

/** A book in the order does not have enough stock to reserve. */
public class InsufficientInventoryException extends RuntimeException {

    public InsufficientInventoryException(UUID bookUuid) {
        super("Not enough inventory for book: " + bookUuid);
    }
}
