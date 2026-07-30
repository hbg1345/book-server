package com.example.bookserver.book.dto;

import java.util.List;
import java.util.UUID;

import com.example.bookserver.book.AuthorSearchResult;

/**
 * A search hit: the author plus the titles of the books they wrote, so the caller
 * can tell homonyms apart before picking an author_uuid to link on a book.
 */
public record AuthorSearchResponse(
        UUID authorUuid,
        String authorName,
        List<String> books) {

    public static AuthorSearchResponse from(AuthorSearchResult result) {
        return new AuthorSearchResponse(
                result.author().getAuthorUuid(),
                result.author().getAuthorName(),
                result.bookTitles());
    }
}
