package com.example.bookserver.payment;

import java.math.BigDecimal;

/** A refund of a previously confirmed charge, addressed by the provider's transaction id. */
public record RefundRequest(
        String providerTransactionId,
        BigDecimal amount,
        String idempotencyKey) {
}
