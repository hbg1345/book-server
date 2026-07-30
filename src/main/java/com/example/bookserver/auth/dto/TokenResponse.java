package com.example.bookserver.auth.dto;

/** Token pair returned by login and refresh. */
public record TokenResponse(String accessToken, String refreshToken) {
}
