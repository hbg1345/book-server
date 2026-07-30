package com.example.bookserver;

/** Thrown when registering a user_id that is already taken. */
public class DuplicateUserIdException extends RuntimeException {

    public DuplicateUserIdException(String userId) {
        super("user_id already taken: " + userId);
    }
}
