package com.example.bookserver.payment;

import java.math.BigDecimal;

/**
 * In-memory {@link PaymentGateway} for tests — no network. Succeeds by default; call
 * {@link #setSucceed(boolean)} to simulate a decline. Records the charge count and the last
 * amount charged so tests can assert the server charged its own order total (not the client's)
 * and that idempotent retries do not double-charge.
 */
public class FakePaymentGateway implements PaymentGateway {

    private boolean succeed = true;
    private boolean refundSucceed = true;
    private int chargeCount = 0;
    private int refundCount = 0;
    private BigDecimal lastChargedAmount;
    private BigDecimal lastRefundedAmount;

    public void setSucceed(boolean succeed) {
        this.succeed = succeed;
    }

    public void setRefundSucceed(boolean refundSucceed) {
        this.refundSucceed = refundSucceed;
    }

    public int chargeCount() {
        return chargeCount;
    }

    public int refundCount() {
        return refundCount;
    }

    public BigDecimal lastChargedAmount() {
        return lastChargedAmount;
    }

    public BigDecimal lastRefundedAmount() {
        return lastRefundedAmount;
    }

    @Override
    public ChargeResult confirm(ChargeRequest request) {
        chargeCount++;
        lastChargedAmount = request.amount();
        return succeed
                ? ChargeResult.paid("fake_txn_" + request.idempotencyKey())
                : ChargeResult.failed("card_declined");
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        refundCount++;
        lastRefundedAmount = request.amount();
        return refundSucceed
                ? RefundResult.refunded("fake_refund_" + request.idempotencyKey())
                : RefundResult.failed("refund_failed");
    }
}
