-- Per-user address book. A user keeps several saved delivery addresses (each with an
-- alias like Home/Work); an order later snapshots the chosen address onto itself, so
-- editing or deleting a saved address never changes a past order (order snapshot: #26/orders).
--
-- Schema is international-shaped: `country` (ISO 3166-1 alpha-2) plus free-form address
-- lines rather than Korea-specific columns, so adding countries needs no migration. The
-- app format-validates per country (KR -> postal_code is 5 digits; other countries skip).
CREATE TABLE address (
    address_uuid   UUID         PRIMARY KEY,
    user_uuid      UUID         NOT NULL,
    alias          VARCHAR(50)  NOT NULL,        -- user's label for this address, e.g. Home/Work
    recipient      VARCHAR(100) NOT NULL,
    phone          VARCHAR(20)  NOT NULL,
    country        CHAR(2)      NOT NULL DEFAULT 'KR',   -- ISO 3166-1 alpha-2
    road_address   VARCHAR(255) NOT NULL,        -- street / road-name address line
    detail_address VARCHAR(255),                 -- unit/floor; optional
    postal_code    VARCHAR(20)  NOT NULL,        -- format validated per country in the app layer
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_uuid) REFERENCES book_user (user_uuid) ON DELETE CASCADE
);

-- Listing a user's address book is a plain indexed scan on user_uuid.
CREATE INDEX idx_address_user ON address (user_uuid);

-- At most one default address per user. Partial unique index (NULLs/false rows are
-- unconstrained), mirroring the category-tree partial-unique pattern in V2.
CREATE UNIQUE INDEX uq_address_one_default ON address (user_uuid) WHERE is_default;
