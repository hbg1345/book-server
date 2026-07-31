package com.example.bookserver.purchase.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.example.bookserver.purchase.OrderBookItem;

/** One book line of an order, with its title, unit price and line subtotal. */
public record OrderItemResponse(
        UUID bookUuid,
        String bookTitle,
        Integer quantity,
        BigDecimal price,
        BigDecimal lineTotal) {

    public static OrderItemResponse from(OrderBookItem item) {
        BigDecimal lineTotal = item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new OrderItemResponse(
                item.getBookUuid(),
                item.getBookTitle(),
                item.getQuantity(),
                item.getPrice(),
                lineTotal);
    }
}
