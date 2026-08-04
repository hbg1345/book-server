package com.example.bookserver.purchase.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body for paying an order (POST /api/orders/{id}/pay). {@code provider} names the gateway the
 * frontend used, {@code paymentKey} is the provider token/key for the authorized charge (also
 * the idempotency key), and {@code amount} is the client's claimed total — the server verifies
 * it against the real order total and charges its own figure.
 */
public record PayRequest(
        @NotBlank String provider,
        @NotBlank String paymentKey,
        @NotNull @Positive BigDecimal amount) {
}
