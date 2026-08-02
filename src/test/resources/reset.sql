-- Per-test data reset. The schema itself is built by Flyway (V1 + V2; the V3 catalog
-- seed is skipped in tests via spring.flyway.target=2), so this script only clears rows
-- between test methods — it never (re)defines tables. CASCADE handles FK order;
-- flyway_schema_history is left untouched so Flyway does not re-run.
TRUNCATE
    refresh_token,
    purchase_book_history,
    purchase_current,
    purchase_history,
    cart_item,
    book_author,
    book,
    category,
    author,
    book_user
RESTART IDENTITY CASCADE;
