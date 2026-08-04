package com.example.bookserver.payment;

/**
 * Outcome of a charge attempt. A decline is a normal result ({@code success=false}), not an
 * exception, so the caller can record a FAILED payment and leave the order unpaid.
 */
public record ChargeResult(boolean success, String providerTransactionId, String failureReason) {

    public static ChargeResult paid(String providerTransactionId) {
        return new ChargeResult(true, providerTransactionId, null);
    }

    public static ChargeResult failed(String failureReason) {
        return new ChargeResult(false, null, failureReason);
    }
}
