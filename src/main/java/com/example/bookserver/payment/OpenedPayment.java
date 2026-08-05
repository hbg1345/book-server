package com.example.bookserver.payment;

/**
 * An opened payment: the persisted {@link Payment} row plus the provider's client secret. The
 * secret is deliberately not part of {@link Payment} — it is handed to the frontend to confirm
 * the card and is never stored. Re-opening the intent for the same order yields it again.
 */
public record OpenedPayment(Payment payment, String clientSecret) {
}
