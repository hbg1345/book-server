package com.example.bookserver.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.example.bookserver.purchase.PurchaseCurrent;
import com.example.bookserver.purchase.PurchaseState;

/** One row in the order list: current state + total, newest first. */
public record OrderSummaryResponse(
        UUID purchaseUuid,
        PurchaseState purchaseState,
        BigDecimal price,
        LocalDateTime updatedAt) {

    public static OrderSummaryResponse from(PurchaseCurrent current) {
        return new OrderSummaryResponse(
                current.getPurchaseUuid(),
                current.getPurchaseState(),
                current.getPrice(),
                current.getUpdatedAt());
    }
}
