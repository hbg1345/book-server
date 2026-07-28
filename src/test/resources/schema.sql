-- Drop first (reverse order because of foreign keys)
DROP TABLE IF EXISTS book_author;
DROP TABLE IF EXISTS book;
DROP TABLE IF EXISTS author;

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
