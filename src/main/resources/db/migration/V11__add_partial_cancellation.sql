-- Room for an order that is partly cancelled.
--
-- Until now cancelling was all or nothing: the whole order went CANCELLED (or REFUNDED) and every
-- line's stock came back. A customer who ordered three books and wanted to drop one had to cancel
-- the lot and order again, which also released stock someone else could take in between.
--
-- Two new states rather than deriving "partly cancelled" from the lines: the order list is a point
-- lookup on purchase_current, and deriving would make it read every order's lines to render a
-- badge. The state is what the client already reads; this keeps it that way.
ALTER TABLE purchase_history DROP CONSTRAINT purchase_history_purchase_state_check;
ALTER TABLE purchase_history ADD CONSTRAINT purchase_history_purchase_state_check
    CHECK (purchase_state IN ('PAYMENT_PENDING','ORDERED','PREPARING','SHIPPING','DELIVERED','CONFIRMED',
                              'CANCEL_REQUESTED','CANCELLED','REFUND_REQUESTED','REFUNDED',
                              'PARTIALLY_CANCELLED','PARTIALLY_REFUNDED'));

ALTER TABLE purchase_current DROP CONSTRAINT purchase_current_purchase_state_check;
ALTER TABLE purchase_current ADD CONSTRAINT purchase_current_purchase_state_check
    CHECK (purchase_state IN ('PAYMENT_PENDING','ORDERED','PREPARING','SHIPPING','DELIVERED','CONFIRMED',
                              'CANCEL_REQUESTED','CANCELLED','REFUND_REQUESTED','REFUNDED',
                              'PARTIALLY_CANCELLED','PARTIALLY_REFUNDED'));

ALTER TABLE purchase_book_history DROP CONSTRAINT purchase_book_history_purchase_state_check;
ALTER TABLE purchase_book_history ADD CONSTRAINT purchase_book_history_purchase_state_check
    CHECK (purchase_state IN ('PAYMENT_PENDING','ORDERED','PREPARING','SHIPPING','DELIVERED','CONFIRMED',
                              'CANCEL_REQUESTED','CANCELLED','REFUND_REQUESTED','REFUNDED',
                              'PARTIALLY_CANCELLED','PARTIALLY_REFUNDED'));

-- How much of this charge has already gone back.
--
-- With one refund per order the status column said everything: PAID or REFUNDED. Partial refunds
-- need a number, both to stop a customer being refunded more than they paid and to let
-- reconciliation see a partly-returned charge for what it is.
--
-- It could be derived by summing what each cancellation returned, but money should not depend on
-- replaying a log correctly — a single arithmetic mistake there silently overpays.
ALTER TABLE payment
    ADD COLUMN refunded_amount DECIMAL(10, 2) NOT NULL DEFAULT 0;

-- Charges already fully refunded predate the column; record that all of their money went back so
-- the invariant "refunded_amount <= amount, and REFUNDED means they are equal" holds everywhere.
UPDATE payment SET refunded_amount = amount WHERE status = 'REFUNDED';
