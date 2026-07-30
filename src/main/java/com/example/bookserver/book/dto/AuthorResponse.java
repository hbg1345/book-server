package com.example.bookserver.book.dto;

import java.util.UUID;

import com.example.bookserver.book.Author;

/** An author as returned inside a {@link BookResponse}. */
public record AuthorResponse(UUID authorUuid, String authorName) {

    public static AuthorResponse from(Author author) {
        return new AuthorResponse(author.getAuthorUuid(), author.getAuthorName());
    }
}
