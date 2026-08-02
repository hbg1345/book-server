-- Category taxonomy as a normalized tree: each node points at its parent
-- (parent_uuid = NULL for a top-level category); a book references the LEAF node
-- of its category path. Nullable — ~25% of catalog rows have no category, and
-- books created via the API do not set one yet. Seeded in V3 from
-- archive/BooksDatasetClean.csv (paths split on the " , " level delimiter).

CREATE TABLE category (
    category_uuid UUID         PRIMARY KEY,
    parent_uuid   UUID         REFERENCES category (category_uuid) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL
);

-- A parent has at most one child of a given name; the two partial indexes also
-- keep top-level names unique (a plain UNIQUE would treat NULL parents as distinct).
CREATE UNIQUE INDEX uq_category_root_name  ON category (name)              WHERE parent_uuid IS NULL;
CREATE UNIQUE INDEX uq_category_child_name ON category (parent_uuid, name) WHERE parent_uuid IS NOT NULL;

-- Subtree walks ("children of X") start from parent_uuid.
CREATE INDEX idx_category_parent ON category (parent_uuid);

-- The catalog includes corporate authors ("University of ... (COR)") whose names
-- exceed the original VARCHAR(100); widen so the V3 seed keeps full names untruncated.
ALTER TABLE author ALTER COLUMN author_name TYPE TEXT;

ALTER TABLE book
    ADD COLUMN category_uuid UUID REFERENCES category (category_uuid) ON DELETE SET NULL;

-- "all books in this (leaf) category".
CREATE INDEX idx_book_category ON book (category_uuid);
