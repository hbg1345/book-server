package com.example.bookserver.book;

import java.util.List;

/** An author plus the titles of the books they wrote (for homonym disambiguation). */
public record AuthorSearchResult(Author author, List<String> bookTitles) {
}
