-- GIN remains useful for filtering literal substring and trigram predicates, but it cannot
-- return the nearest titles in similarity order. GiST supports pg_trgm distance ordering, so
-- the search query can stop after the rows needed for the requested page instead of scoring and
-- sorting every match. A longer-than-default signature reduces false positives on this
-- read-heavy ~103k-row catalogue at the cost of a larger index.
CREATE INDEX idx_book_title_trgm_gist
    ON book USING GIST (book_title gist_trgm_ops(siglen=32));
