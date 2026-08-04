package com.example.bookserver.payment;

/**
 * Port to a payment provider. The domain depends on this abstraction, not on any vendor;
 * each provider (Toss, Stripe, …) is a separate adapter implementing it. Modeled on the common
 * card charge flow: the frontend tokenizes the card and the backend confirms the charge here.
 * Refund is added in #25 PR-2.
 */
public interface PaymentGateway {

    /** Confirm/capture a charge the frontend already authorized. Never throws on a decline —
     *  a declined card is a {@link ChargeResult} with {@code success=false}. */
    ChargeResult confirm(ChargeRequest request);
}
