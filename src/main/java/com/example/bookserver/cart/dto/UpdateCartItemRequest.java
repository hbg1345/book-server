package com.example.bookserver.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Body for changing the quantity of a cart item (PUT /api/cart/items/{bookUuid}). */
public record UpdateCartItemRequest(
        @NotNull @Min(1) Integer quantity) {
}
