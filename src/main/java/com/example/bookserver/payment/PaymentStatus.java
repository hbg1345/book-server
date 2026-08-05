package com.example.bookserver.payment;

/**
 * Lifecycle of a payment. PENDING once the intent is opened and the frontend may confirm it; the
 * provider's webhook then reports PAID or FAILED; REFUNDED is set when a paid order is refunded.
 * Stored in the DB as the enum name via MyBatis' default EnumTypeHandler.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED,
    /**
     * The refund was accepted and then reversed — the bank rejected the credit, the card is
     * closed. The order stays REFUNDED because the buyer was already told so and their stock was
     * already returned; only this row records that the money did not actually go back. Terminal:
     * it needs a human, not a retry.
     */
    REFUND_FAILED
}
