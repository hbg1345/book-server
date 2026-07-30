package com.example.bookserver.user;

/** Thrown on login when the id is unknown or the password is wrong (kept generic on purpose). */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {

        super("invalid id or password");
    }
}
