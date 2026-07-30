package com.example.bookserver.user;

/** Thrown when a supplied current password does not match the stored hash. */
public class InvalidPasswordException extends RuntimeException {

    public InvalidPasswordException() {
        super("current password does not match");
    }
}
