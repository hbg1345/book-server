package com.example.bookserver.purchase;

import java.util.UUID;

/** No such order for this user (missing, or owned by someone else). */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID purchaseUuid) {
        super("Order not found: " + purchaseUuid);
    }
}
