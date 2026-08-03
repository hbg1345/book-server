-- Role-based access control: every user carries a role. Catalog writes (book/author)
-- are restricted to ADMIN in the security layer; everyone else defaults to USER.
-- Ordered before the heavy V4 catalog seed so tests (spring.flyway.target=3) get this
-- column without loading the seed.
ALTER TABLE book_user
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER'
        CHECK (role IN ('USER', 'ADMIN'));
