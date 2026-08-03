package com.example.bookserver.user;

/**
 * A user's role, the basis for authorization. {@code USER} is the default for every
 * registered account; {@code ADMIN} additionally may write to the catalog (create/update/
 * delete books and authors). Stored as its name in {@code book_user.role} and carried in
 * the access token as a {@code role} claim (surfaced as the {@code ROLE_<name>} authority).
 */
public enum Role {
    USER,
    ADMIN
}
