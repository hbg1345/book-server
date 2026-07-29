-- Drop first (reverse order because of foreign keys)
DROP TABLE IF EXISTS cart_item;
DROP TABLE IF EXISTS book_author;
DROP TABLE IF EXISTS book;
DROP TABLE IF EXISTS author;
DROP TABLE IF EXISTS book_user;

-- Books
CREATE TABLE book (
    book_uuid        UUID           PRIMARY KEY,
    book_title       VARCHAR(255)   NOT NULL,
    book_description TEXT,
    price            DECIMAL(10, 2) NOT NULL,
    publish_date     DATE           NOT NULL,
    publisher        VARCHAR(100)   NOT NULL,
    inventory        INT            NOT NULL
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
