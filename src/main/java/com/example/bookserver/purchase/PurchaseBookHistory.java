package com.example.bookserver.purchase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-book state within an order state-change event (append-only).
 * Keyed by (history_uuid, book_uuid): for a given purchase_history event, one row
 * per book capturing that book's state/quantity/price. Read by history_uuid — the
 * leftmost prefix of the PK — so it needs no current/history split.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseBookHistory {

    private UUID historyUuid;       // the purchase_history state event this row belongs to
    private UUID bookUuid;
    private PurchaseState purchaseState;
    private Integer quantity;
    private BigDecimal price;       // per-book price snapshot at that event
    private LocalDateTime updatedAt;
}
