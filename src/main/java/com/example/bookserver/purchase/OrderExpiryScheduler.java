package com.example.bookserver.purchase;

import java.util.UUID;

/**
 * Schedules the per-order expiry of a freshly placed order: at the end of the payment
 * window, something should call the single-order expiry endpoint so the order is cancelled
 * and its reserved stock released if it is still unpaid.
 *
 * <p>This is the precise, per-order half of the hybrid design; the periodic sweep
 * ({@link UnpaidOrderSweeper}) is the safety net that catches orders whose scheduling
 * failed (the enqueue and the order's DB commit are separate systems and can diverge).
 * Implementations must therefore be best-effort: a scheduling failure must never fail the
 * order placement — log and rely on the sweep.
 */
public interface OrderExpiryScheduler {

    /** Best-effort: arrange for {@code purchaseUuid} to be expiry-checked after the payment window. */
    void scheduleExpiry(UUID purchaseUuid);
}
