-- Role-based access control: every user carries a role. Catalog writes (book/author)
-- are restricted to ADMIN in the security layer; everyone else defaults to USER.
ALTER TABLE book_user
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER'
        CHECK (role IN ('USER', 'ADMIN'));
