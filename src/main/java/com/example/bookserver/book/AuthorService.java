package com.example.bookserver.book;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.bookserver.common.Uuids;

@Service
public class AuthorService {

    private final AuthorMapper authorMapper;

    public AuthorService(AuthorMapper authorMapper) {
        this.authorMapper = authorMapper;
    }

    /** Create an author and return the generated author_uuid. */
    public UUID create(String authorName) {
        UUID authorUuid = Uuids.newId();
        authorMapper.insert(new Author(authorUuid, authorName));
        return authorUuid;
    }

    /**
     * Find authors by exact name. Each hit carries the titles of the books that
     * author wrote so callers can tell homonyms apart. (Author names are not
     * unique, so this may return several distinct authors.)
     */
    public List<AuthorSearchResult> searchByName(String name) {
        return authorMapper.findByName(name).stream()
                .map(author -> new AuthorSearchResult(
                        author, authorMapper.findBookTitlesByAuthorId(author.getAuthorUuid())))
                .toList();
    }
}
