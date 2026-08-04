package com.example.bookserver.payment;

import java.util.UUID;

/** The gateway could not refund the charge; the order is left unchanged for a retry. HTTP 502. */
public class RefundFailedException extends RuntimeException {

    public RefundFailedException(UUID purchaseUuid) {
        super("Refund failed for order " + purchaseUuid);
    }
}
