-- Give `book` an identity the database can enforce.
--
-- Until now the only key was book_uuid, minted fresh on every POST /api/books, so nothing stopped
-- the same book being registered twice — and the second row brings its own inventory, so a
-- double-submitted "inventory: 10" leaves the catalogue offering 20 copies of a book the shop has
-- 10 of. Those extra copies can be ordered and paid for.
--
-- (title, publisher, publish_date) is the closest thing to a natural key this schema has. There is
-- no ISBN column; adding one and keying on it would be the better answer, and this constraint is
-- meant to be replaced by that rather than to stand forever.
--
-- The cost is real: two editions a publisher released on the same day — hardback and paperback —
-- are now indistinguishable and only one may be listed. That is judged the lesser problem than
-- inventing stock, and it is visible (the second insert is rejected) rather than silent.

-- The seed carries 88 groups of duplicates, 176 rows, so the constraint cannot simply be added.
--
-- Rows that appear in an order's history are never deleted: purchase_book_history references book
-- WITHOUT cascade, and that log is the record of what a customer actually bought. Where a group
-- has such a row it is the one kept; otherwise the lowest uuid wins, so the choice is
-- deterministic and a re-run picks the same survivor.
--
-- If any group holds two rows that are BOTH in order history, this migration fails at the ALTER
-- below rather than deleting evidence or repointing a customer's order at a different book. That
-- failure needs a person, which is the correct outcome — it means two genuinely distinct books
-- have been sold under one identity.
WITH ranked AS (
    SELECT b.book_uuid,
           row_number() OVER (
               PARTITION BY b.book_title, b.publisher, b.publish_date
               ORDER BY EXISTS (SELECT 1 FROM purchase_book_history h WHERE h.book_uuid = b.book_uuid) DESC,
                        b.book_uuid
           ) AS rn
    FROM book b
),
doomed AS (
    SELECT r.book_uuid
    FROM ranked r
    WHERE r.rn > 1
      AND NOT EXISTS (SELECT 1 FROM purchase_book_history h WHERE h.book_uuid = r.book_uuid)
)
DELETE FROM book WHERE book_uuid IN (SELECT book_uuid FROM doomed);

-- book_author and cart_item cascade, so the deletes above took their rows with them.

ALTER TABLE book
    ADD CONSTRAINT unq_book_identity UNIQUE (book_title, publisher, publish_date);
