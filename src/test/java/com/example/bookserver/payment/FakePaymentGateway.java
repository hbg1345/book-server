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
    private int chargeCount = 0;
    private BigDecimal lastChargedAmount;

    public void setSucceed(boolean succeed) {
        this.succeed = succeed;
    }

    public int chargeCount() {
        return chargeCount;
    }

    public BigDecimal lastChargedAmount() {
        return lastChargedAmount;
    }

    @Override
    public ChargeResult confirm(ChargeRequest request) {
        chargeCount++;
        lastChargedAmount = request.amount();
        return succeed
                ? ChargeResult.paid("fake_txn_" + request.idempotencyKey())
                : ChargeResult.failed("card_declined");
    }
}
