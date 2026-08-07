package com.example.bookserver.book;

import java.util.UUID;

/**
 * A stock movement would leave the book holding fewer than zero copies. Distinct from
 * {@link com.example.bookserver.purchase.InsufficientInventoryException}, which a shopper meets
 * when the shop cannot fill their order: this one is an administrator writing off more copies
 * than the shop has. Mapped to 409.
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(UUID bookUuid, int delta) {
        super("Book " + bookUuid + " does not hold enough copies to apply a change of " + delta);
    }
}
