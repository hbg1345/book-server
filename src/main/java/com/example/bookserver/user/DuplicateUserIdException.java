package com.example.bookserver.user;

/** Thrown when registering a user_id that is already taken. */
public class DuplicateUserIdException extends RuntimeException {

    public DuplicateUserIdException(String userId) {
        super("user_id already taken: " + userId);
    }
}
