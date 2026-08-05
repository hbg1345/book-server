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
    REFUNDED
}
