package com.example.bookserver.payment;

/**
 * Outcome of opening a payment intent. Note this is NOT the outcome of the charge: the card is
 * authorized later, between the browser and the provider, and reaches us as a webhook. Success
 * here only means "an intent exists and the client may now confirm it".
 *
 * <p>{@code clientSecret} is handed to the frontend to confirm the charge; it is never persisted.
 * {@code providerIntentId} is the provider's handle for the intent, which we do persist — a
 * later refund is addressed by it.
 */
public record IntentResult(boolean success, String providerIntentId, String clientSecret,
                           String failureReason) {

    public static IntentResult opened(String providerIntentId, String clientSecret) {
        return new IntentResult(true, providerIntentId, clientSecret, null);
    }

    public static IntentResult failed(String failureReason) {
        return new IntentResult(false, null, null, failureReason);
    }
}
