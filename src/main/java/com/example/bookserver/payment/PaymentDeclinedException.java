package com.example.bookserver.payment;

import java.util.UUID;

/** The gateway declined the charge; the order stays unpaid. Surfaces as HTTP 402. */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(UUID purchaseUuid) {
        super("Payment was declined for order " + purchaseUuid);
    }
}
