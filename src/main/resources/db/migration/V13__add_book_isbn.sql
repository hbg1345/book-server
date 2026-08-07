-- ISBN: the book's identity in the outside world, as opposed to book_uuid, which is only its
-- identity in here. A surrogate key answers "which row", a natural key answers "which book" —
-- and only the second one can tell that two rows are the same title entered twice.
--
-- That is what this column is for. POST /api/books mints a fresh book_uuid per call, so a
-- double-clicked form used to leave two identical books in the catalogue, each with its own
-- stock. A unique ISBN makes the second insert collide instead.
--
-- The seeded catalogue has no real ISBNs to load: it comes from a Kaggle dataset whose columns
-- are title/authors/description/category/publisher/price/date and nothing else. Rather than
-- leave 103k rows null and a constraint that never fires, they are assigned here — valid
-- ISBN-13s (978 prefix, correct check digit) that are structurally real and factually invented.
-- They identify rows in this database and nothing beyond it. Anything created through the API
-- carries a real one, which the API requires and validates.

ALTER TABLE book ADD COLUMN isbn VARCHAR(13);

-- Assign sequentially rather than hashing book_uuid: 103k values drawn from the 10^9 the 978
-- prefix leaves would collide about five times by the birthday bound, and a UNIQUE index that
-- fails on a fifth of a percent of loads is worse than no index at all. Publishers are allotted
-- contiguous blocks anyway, so a run of consecutive numbers is the realistic shape.
WITH numbered AS (
    SELECT book_uuid,
           '978' || lpad((100000000 + row_number() OVER (ORDER BY book_uuid))::text, 9, '0') AS body
    FROM book
)
UPDATE book b
SET isbn = n.body || ((10 - (
        -- ISBN-13 check digit: digits weighted 1,3,1,3,... left to right, complement mod 10.
        SELECT sum(substr(n.body, i, 1)::int * CASE WHEN i % 2 = 1 THEN 1 ELSE 3 END)
        FROM generate_series(1, 12) AS i
    ) % 10) % 10)::text
FROM numbered n
WHERE b.book_uuid = n.book_uuid;

-- Safe on an empty table too: the tests skip the catalog seed (catalogSeed.skip), so the UPDATE
-- above matches nothing there and every book they create supplies its own.
ALTER TABLE book ALTER COLUMN isbn SET NOT NULL;

CREATE UNIQUE INDEX uq_book_isbn ON book (isbn);
