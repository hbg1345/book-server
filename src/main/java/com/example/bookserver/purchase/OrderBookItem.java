package com.example.bookserver.purchase;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One book line of an order state event, joined with the book's title so the order
 * detail can show it without a second lookup. Read-only projection over
 * {@code purchase_book_history ⨝ book}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderBookItem {

    private UUID bookUuid;
    private String bookTitle;
    private Integer quantity;
    private BigDecimal price;       // per-book price snapshot at that event
}
