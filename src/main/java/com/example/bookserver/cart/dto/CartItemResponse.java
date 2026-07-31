package com.example.bookserver.cart.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.example.bookserver.cart.CartItemView;

/** One line in the user's cart, enriched with the book's title, price and line subtotal. */
public record CartItemResponse(
        UUID bookUuid,
        String bookTitle,
        BigDecimal price,
        Integer quantity,
        BigDecimal lineTotal,
        LocalDateTime createdAt) {

    public static CartItemResponse from(CartItemView view) {
        BigDecimal lineTotal = view.getPrice().multiply(BigDecimal.valueOf(view.getQuantity()));
        return new CartItemResponse(
                view.getBookUuid(),
                view.getBookTitle(),
                view.getPrice(),
                view.getQuantity(),
                lineTotal,
                view.getCreatedAt());
    }
}
