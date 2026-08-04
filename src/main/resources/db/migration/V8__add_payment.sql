-- Persisted payment record, one row per charge attempt against an order (a failed attempt and
-- a later successful one are separate rows). Kept for auditing/reconciliation. `provider` names
-- which gateway handled it (so a refund later goes back to the same one) and `idempotency_key`
-- is the dedup unit that guards against a double charge — a retry with the same key is a no-op.
CREATE TABLE payment (
    payment_uuid    UUID          PRIMARY KEY,
    purchase_uuid   UUID          NOT NULL REFERENCES purchase_current (purchase_uuid) ON DELETE CASCADE,
    provider        VARCHAR(20)   NOT NULL,          -- e.g. TOSS (no CHECK: providers are added freely)
    provider_txn_id VARCHAR(255),                    -- the provider's transaction id (null if it never confirmed)
    amount          DECIMAL(10, 2) NOT NULL,
    status          VARCHAR(20)   NOT NULL
        CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'REFUNDED')),
    idempotency_key VARCHAR(255)  NOT NULL UNIQUE,   -- same key => same charge, never a second one
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- "the payment(s) for this order".
CREATE INDEX idx_payment_purchase ON payment (purchase_uuid);
