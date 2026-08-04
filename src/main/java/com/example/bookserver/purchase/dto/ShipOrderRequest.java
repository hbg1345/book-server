package com.example.bookserver.purchase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for shipping an order (POST /api/orders/{id}/ship): the carrier tracking number. */
public record ShipOrderRequest(
        @NotBlank @Size(max = 64) String trackingNumber) {
}
