package com.example.bookserver;

import java.util.UUID;

/** Thrown when an operation targets a user_uuid that does not exist. */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID userUuid) {
        super("user not found: " + userUuid);
    }
}
