package com.example.bookserver.payment;

/**
 * Lifecycle of a single payment record. A charge attempt is PAID on success or FAILED
 * otherwise; REFUNDED is set when a paid order is later refunded (#25 PR-2). Stored in the
 * DB as the enum name via MyBatis' default EnumTypeHandler.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED
}
