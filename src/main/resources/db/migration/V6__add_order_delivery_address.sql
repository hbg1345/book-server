-- Delivery address snapshotted onto an order. One row per order (purchase_uuid), copied
-- from either a saved address-book entry or a one-off address supplied at order time. The
-- values are COPIED (not a FK to `address`), so editing or deleting the saved address never
-- mutates a past order. Kept in its own 1:1 table rather than on purchase_current so it is
-- written once at order time and stays untouched by the append-only state-transition churn.
--
-- Same international shape as `address` (V5): `country` (ISO 3166-1 alpha-2) + free-form
-- lines, format-validated per country in the app layer. `alias`/`is_default` are address-book
-- concepts and are deliberately not snapshotted.
CREATE TABLE order_address (
    purchase_uuid       UUID         PRIMARY KEY
        REFERENCES purchase_current (purchase_uuid) ON DELETE CASCADE,
    recipient           VARCHAR(100) NOT NULL,
    phone               VARCHAR(20)  NOT NULL,
    country             CHAR(2)      NOT NULL DEFAULT 'KR',   -- ISO 3166-1 alpha-2
    road_address        VARCHAR(255) NOT NULL,
    detail_address      VARCHAR(255),                          -- unit/floor; optional
    postal_code         VARCHAR(20)  NOT NULL,
    -- Breadcrumb: which saved address this snapshot was copied from (NULL for a one-off
    -- inline address). Deliberately NOT a foreign key — the address may later be edited or
    -- deleted, and that must never touch this immutable snapshot.
    source_address_uuid UUID,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
