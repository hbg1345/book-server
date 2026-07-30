package com.example.bookserver.common;

/** Thrown when a request that needs a logged-in user has no session user. */
public class NotLoggedInException extends RuntimeException {

    public NotLoggedInException() {
        super("login required");
    }
}
