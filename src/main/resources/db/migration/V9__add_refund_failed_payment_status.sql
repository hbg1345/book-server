-- A refund the provider accepted can still be reversed afterwards — the bank rejects the credit,
-- the card is closed — and that arrives as a webhook long after we recorded the refund. Without a
-- status for it the payment row keeps claiming REFUNDED while the customer has no money back, so
-- reconciliation has nothing to look for. REFUND_FAILED is that marker; it is a terminal state
-- needing a human, not something the application recovers from on its own.
ALTER TABLE payment DROP CONSTRAINT payment_status_check;

ALTER TABLE payment ADD CONSTRAINT payment_status_check
    CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'REFUNDED', 'REFUND_FAILED'));
