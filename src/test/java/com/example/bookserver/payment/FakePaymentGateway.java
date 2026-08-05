package com.example.bookserver.payment;

import java.math.BigDecimal;

/**
 * In-memory {@link PaymentGateway} for tests — no network. Opens intents successfully by default;
 * call {@link #setOpenSucceed(boolean)} to simulate a provider that will not. Records how many
 * intents were opened and the last amount, so tests can assert the server passed its OWN order
 * total and that a retry replays one intent rather than opening a second.
 */
public class FakePaymentGateway implements PaymentGateway {

    private boolean openSucceed = true;
    private boolean refundSucceed = true;
    private int openCount = 0;
    private int refundCount = 0;
    private BigDecimal lastIntentAmount;
    private BigDecimal lastRefundedAmount;

    public void setOpenSucceed(boolean openSucceed) {
        this.openSucceed = openSucceed;
    }

    public void setRefundSucceed(boolean refundSucceed) {
        this.refundSucceed = refundSucceed;
    }

    public int openCount() {
        return openCount;
    }

    public int refundCount() {
        return refundCount;
    }

    public BigDecimal lastIntentAmount() {
        return lastIntentAmount;
    }

    public BigDecimal lastRefundedAmount() {
        return lastRefundedAmount;
    }

    @Override
    public String provider() {
        return "FAKE";
    }

    @Override
    public IntentResult openIntent(IntentRequest request) {
        openCount++;
        lastIntentAmount = request.amount();
        // the id is derived from the key, mirroring a real provider replaying an idempotent retry
        return openSucceed
                ? IntentResult.opened("pi_" + request.idempotencyKey(), "cs_" + request.idempotencyKey())
                : IntentResult.failed("provider_unavailable");
    }

    @Override
    public boolean credentialsValid() {
        return true;   // nothing to authenticate against
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
