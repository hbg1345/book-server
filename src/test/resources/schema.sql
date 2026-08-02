-- Drop first (reverse order because of foreign keys)
DROP TABLE IF EXISTS refresh_token;
DROP TABLE IF EXISTS purchase_book_history;
DROP TABLE IF EXISTS purchase_current;
DROP TABLE IF EXISTS purchase_history;
DROP TABLE IF EXISTS cart_item;
DROP TABLE IF EXISTS book_author;
DROP TABLE IF EXISTS book;
DROP TABLE IF EXISTS category;
DROP TABLE IF EXISTS author;
DROP TABLE IF EXISTS book_user;

-- Category taxonomy (normalized tree; parent_uuid NULL = top-level category)
CREATE TABLE category (
    category_uuid UUID         PRIMARY KEY,
    parent_uuid   UUID         REFERENCES category (category_uuid) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    depth         INT          NOT NULL
);
-- A parent has at most one child of a given name (and only one top-level of a name).
CREATE UNIQUE INDEX uq_category_root_name  ON category (name)              WHERE parent_uuid IS NULL;
CREATE UNIQUE INDEX uq_category_child_name ON category (parent_uuid, name) WHERE parent_uuid IS NOT NULL;

-- Books
CREATE TABLE book (
    book_uuid        UUID           PRIMARY KEY,
    book_title       VARCHAR(255)   NOT NULL,
    book_description TEXT,
    price            DECIMAL(10, 2) NOT NULL,
    publish_date     DATE           NOT NULL,
    publisher        VARCHAR(100)   NOT NULL,
    inventory        INT            NOT NULL,
    category_uuid    UUID           REFERENCES category (category_uuid) ON DELETE SET NULL
);

-- Authors
CREATE TABLE author (
    author_uuid UUID         PRIMARY KEY,
    author_name VARCHAR(100) NOT NULL
);

-- Join table for the many-to-many relation between book and author
CREATE TABLE book_author (
    book_uuid   UUID NOT NULL,
    author_uuid UUID NOT NULL,
    PRIMARY KEY (book_uuid, author_uuid),
    FOREIGN KEY (book_uuid)   REFERENCES book (book_uuid)     ON DELETE CASCADE,
    FOREIGN KEY (author_uuid) REFERENCES author (author_uuid) ON DELETE CASCADE
);

-- Users
CREATE TABLE book_user (
    user_uuid     UUID         PRIMARY KEY,
    user_id       VARCHAR(100) NOT NULL UNIQUE,
    user_password VARCHAR(255) NOT NULL,   -- stores a password hash (bcrypt/argon2), not plaintext
    user_name     VARCHAR(100) NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    birth_date    DATE         NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Refresh tokens: one row per issued refresh token. Rotation keeps the old row
-- (marked used) so a replayed/stolen token can be detected. The opaque random
-- token itself is never stored — only its SHA-256 hash.
CREATE TABLE refresh_token (
    token_id   UUID        PRIMARY KEY,
    family_id  UUID        NOT NULL,               -- all rotations of one login share this
    user_uuid  UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,        -- SHA-256 hex of the opaque token
    used       BOOLEAN     NOT NULL DEFAULT FALSE, -- rotated away; reuse if presented again
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE, -- family killed (reuse detected / logout)
    expires_at TIMESTAMP   NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_uuid) REFERENCES book_user (user_uuid) ON DELETE CASCADE
);

-- Revoking a whole family (on reuse detection or logout) scans by family_id.
CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);

-- Cart items: one row per (user, book); quantity tracks how many of that book
CREATE TABLE cart_item (
    user_uuid  UUID      NOT NULL,
    book_uuid  UUID      NOT NULL,
    quantity   INT       NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_uuid, book_uuid),
    FOREIGN KEY (user_uuid) REFERENCES book_user (user_uuid) ON DELETE CASCADE,
    FOREIGN KEY (book_uuid) REFERENCES book (book_uuid)      ON DELETE CASCADE
);

-- Order-level state history (append-only): one row per state change of a purchase.
-- This is now purely the audit log; the "current state" lives in purchase_current.
CREATE TABLE purchase_history (
    history_uuid   UUID          PRIMARY KEY,
    purchase_uuid  UUID          NOT NULL,   -- stable id of the purchase (repeats across rows)
    user_uuid      UUID          NOT NULL,
    purchase_state VARCHAR(20)   NOT NULL
        CHECK (purchase_state IN ('PAYMENT_PENDING','ORDERED','PREPARING','SHIPPING','DELIVERED','CONFIRMED',
                                  'CANCEL_REQUESTED','CANCELLED','REFUND_REQUESTED','REFUNDED')),
    price          DECIMAL(10,2) NOT NULL,   -- order total snapshot
    updated_at     TIMESTAMP     NOT NULL,   -- the moment this state took effect (set by the app)
    FOREIGN KEY (user_uuid) REFERENCES book_user (user_uuid) ON DELETE CASCADE
);

-- Order-level CURRENT state: exactly one row per purchase (PK = purchase_uuid),
-- upserted on every state change. Reading a user's latest order states is then a
-- plain indexed range scan on user_uuid — no ORDER BY + DISTINCT over the log.
CREATE TABLE purchase_current (
    purchase_uuid  UUID          PRIMARY KEY,
    user_uuid      UUID          NOT NULL,
    history_uuid   UUID          NOT NULL,   -- head pointer: the history event that IS the current state
    purchase_state VARCHAR(20)   NOT NULL
        CHECK (purchase_state IN ('PAYMENT_PENDING','ORDERED','PREPARING','SHIPPING','DELIVERED','CONFIRMED',
                                  'CANCEL_REQUESTED','CANCELLED','REFUND_REQUESTED','REFUNDED')),
    price          DECIMAL(10,2) NOT NULL,   -- order total snapshot (latest)
    updated_at     TIMESTAMP     NOT NULL,   -- the moment the current state took effect
    FOREIGN KEY (user_uuid)    REFERENCES book_user (user_uuid)           ON DELETE CASCADE,
    -- the current state must be a real logged event; deleting the log cleans up current
    FOREIGN KEY (history_uuid) REFERENCES purchase_history (history_uuid) ON DELETE CASCADE
);

-- The hot path: "give me the current state of all of this user's purchases".
CREATE INDEX idx_purchase_current_user ON purchase_current (user_uuid);

-- Per-book state history (append-only): for each order state-change event
-- (purchase_history.history_uuid), one row per book capturing that book's
-- state/quantity/price at that event. Looked up by history_uuid, which is the
-- leftmost prefix of the PK — so, unlike the order-level state, it is already
-- served by an index and needs no current/history split.
CREATE TABLE purchase_book_history (
    history_uuid   UUID          NOT NULL,   -- the purchase_history state event this row belongs to
    book_uuid      UUID          NOT NULL,
    purchase_state VARCHAR(20)   NOT NULL
        CHECK (purchase_state IN ('PAYMENT_PENDING','ORDERED','PREPARING','SHIPPING','DELIVERED','CONFIRMED',
                                  'CANCEL_REQUESTED','CANCELLED','REFUND_REQUESTED','REFUNDED')),
    quantity       INT           NOT NULL,
    price          DECIMAL(10,2) NOT NULL,   -- per-book price snapshot at that event
    updated_at     TIMESTAMP     NOT NULL,
    PRIMARY KEY (history_uuid, book_uuid),
    FOREIGN KEY (history_uuid) REFERENCES purchase_history (history_uuid) ON DELETE CASCADE,
    FOREIGN KEY (book_uuid)    REFERENCES book (book_uuid)
);
