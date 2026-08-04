-- Per-test data reset. The schema itself is built by Flyway: all migrations (V1..V5) run,
-- but the V3 catalog seed copies nothing because tests set the `catalogSeed.skip` system
-- property (see build.gradle) — we do NOT use spring.flyway.target, which would break
-- Flyway validation against prod. So this script only clears rows between test methods — it
-- never (re)defines tables. CASCADE handles FK order; flyway_schema_history is left
-- untouched so Flyway does not re-run.
TRUNCATE
    refresh_token,
    purchase_book_history,
    order_address,
    purchase_current,
    purchase_history,
    address,
    cart_item,
    book_author,
    book,
    category,
    author,
    book_user
RESTART IDENTITY CASCADE;
