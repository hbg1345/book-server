package com.example.bookserver.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A payment intent to open with the provider. {@code amount} is the SERVER's order total — the
 * client never supplies a figure, so there is nothing to tamper with. {@code idempotencyKey} is
 * order-scoped, so a retry replays the provider's original intent (same client secret) instead
 * of opening a second one. Currency is the adapter's concern, not the domain's.
 */
public record IntentRequest(UUID purchaseUuid, BigDecimal amount, String idempotencyKey) {
}
