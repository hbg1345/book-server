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

    /**
     * Whether the provider accepts this deployment's credentials right now.
     *
     * <p>Exists because nothing else proves it. A key is not checked when the client is built, so
     * a wrong or unreadable one deploys clean, passes health checks, and is discovered by the
     * first customer who tries to pay. This gives the deploy pipeline something to ask.
     *
     * <p>Read-only and cheap by contract — implementations must not create or move anything.
     */
    boolean credentialsValid();
}
