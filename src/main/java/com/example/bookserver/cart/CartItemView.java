package com.example.bookserver.cart;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A cart row joined with the book it references, so the cart view can show the
 * title and price without a second lookup. Read-only projection (no insert).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CartItemView {

    private UUID bookUuid;
    private String bookTitle;
    private BigDecimal price;
    private Integer quantity;
    private LocalDateTime createdAt;
}
