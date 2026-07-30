package com.example.bookserver.auth;

/** Thrown when a presented refresh token is unknown, expired, revoked, or replayed. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("invalid refresh token");
    }
}
