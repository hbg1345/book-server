package com.example.bookserver.book.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body for moving a book's stock (POST /api/books/{uuid}/stock): a change, not a total.
 * Positive receives copies, negative writes them off.
 *
 * <p>A delta needs no knowledge of the current figure, so there is no figure to go stale
 * between reading and sending, and two movements compose whatever order they arrive in — ten
 * received and three sold leave seven either way round. A total cannot do that: the later one
 * simply erases the earlier, which is the bug this endpoint exists to end.
 *
 * <p>What a delta gives up is idempotency, which a total has for free: sending "+20" twice
 * receives forty copies. Nothing here buys that back — the request carries no identity, so the
 * server cannot tell a retry from a second, genuine receipt of twenty more copies. Stopping the
 * repeat is the frontend's job.
 *
 * @param delta copies to add (positive) or remove (negative); zero is a no-op
 */
public record AdjustStockRequest(@NotNull Integer delta) {
}
