package com.example.bookserver.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A charge to confirm with the provider. {@code amount} is the SERVER's order total (never the
 * client's claimed number), and {@code idempotencyKey} lets the provider (and us) dedup retries.
 */
public record ChargeRequest(
        UUID purchaseUuid,
        BigDecimal amount,
        String currency,
        String paymentKey,
        String idempotencyKey) {
}
