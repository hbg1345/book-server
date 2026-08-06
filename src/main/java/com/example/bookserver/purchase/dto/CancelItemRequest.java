package com.example.bookserver.purchase.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * How many copies of one book to drop from an order.
 *
 * <p>Required rather than defaulting to "all of them": cancelling more than intended is not
 * something a client should be able to do by omitting a field.
 */
public record CancelItemRequest(@NotNull @Min(1) Integer quantity) {
}
