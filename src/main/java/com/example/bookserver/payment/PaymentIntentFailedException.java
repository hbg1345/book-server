package com.example.bookserver.payment;

import java.util.UUID;

/**
 * The provider would not open a payment intent (misconfigured key, provider outage, …). Nothing
 * was charged and the order is untouched, so the client may simply try again. Surfaces as HTTP 502
 * — this is our/the provider's failure, not the caller's.
 */
public class PaymentIntentFailedException extends RuntimeException {

    public PaymentIntentFailedException(UUID purchaseUuid, String reason) {
        super("Could not open a payment intent for order " + purchaseUuid + ": " + reason);
    }
}
