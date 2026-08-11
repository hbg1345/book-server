-- A B-tree cannot accelerate a substring predicate such as ILIKE '%term%'. pg_trgm
-- indexes the title's three-character fragments so PostgreSQL can narrow the candidates
-- before evaluating the existing case-insensitive match.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_book_title_trgm
    ON book USING GIN (book_title gin_trgm_ops);
