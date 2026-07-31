package com.example.bookserver.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.example.bookserver.purchase.PurchaseHistory;
import com.example.bookserver.purchase.PurchaseState;

/** One entry in an order's append-only state timeline. */
public record OrderStateEventResponse(
        PurchaseState purchaseState,
        BigDecimal price,
        LocalDateTime updatedAt) {

    public static OrderStateEventResponse from(PurchaseHistory event) {
        return new OrderStateEventResponse(
                event.getPurchaseState(),
                event.getPrice(),
                event.getUpdatedAt());
    }
}
