package com.example.bookserver.payment;

/**
 * Port to a payment provider. The domain depends on this abstraction, not on any vendor; each
 * provider (Stripe, Toss, …) is a separate adapter implementing it.
 *
 * <p>Modeled on the payment-intent flow: the server opens an intent for its own order total and
 * hands the client secret to the frontend, which confirms the card directly with the provider.
 * The charge therefore does NOT complete inside {@link #openIntent} — it lands later as a
 * webhook. Refunds, by contrast, are server-to-server and do complete synchronously.
 */
public interface PaymentGateway {

    /** Name recorded on the payment row, so a later refund goes back to the same provider. */
    String provider();

    /** Open an intent the frontend can confirm. A provider-side failure is an {@link IntentResult}
     *  with {@code success=false}, not an exception. */
    IntentResult openIntent(IntentRequest request);

    /** Refund a completed charge. A failure is a {@link RefundResult} with
     *  {@code success=false}, not an exception. */
    RefundResult refund(RefundRequest request);
}
