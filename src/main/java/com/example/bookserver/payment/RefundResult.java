package com.example.bookserver.payment;

/** Outcome of a refund attempt. */
public record RefundResult(boolean success, String refundTransactionId, String failureReason) {

    public static RefundResult refunded(String refundTransactionId) {
        return new RefundResult(true, refundTransactionId, null);
    }

    public static RefundResult failed(String failureReason) {
        return new RefundResult(false, null, failureReason);
    }
}
