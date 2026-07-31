package com.example.bookserver.cart.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Body for adding a book to the cart (POST /api/cart/items). */
public record AddCartItemRequest(
        @NotNull UUID bookUuid,
        @NotNull @Min(1) Integer quantity) {
}
