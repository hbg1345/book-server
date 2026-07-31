package com.example.bookserver.purchase;

import java.util.List;

/**
 * Assembled view of one order: its current header ({@link PurchaseCurrent}), the books
 * in its current state event, and the full append-only state timeline. Service-level
 * holder; the controller maps it to the response DTO.
 */
public record OrderDetail(
        PurchaseCurrent current,
        List<OrderBookItem> items,
        List<PurchaseHistory> history) {
}
