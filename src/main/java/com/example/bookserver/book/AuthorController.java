package com.example.bookserver.book;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.book.dto.AuthorSearchResponse;
import com.example.bookserver.book.dto.CreateAuthorRequest;
import com.example.bookserver.book.dto.CreateAuthorResponse;

import jakarta.validation.Valid;

/**
 * Author endpoints. Authors are created here and then picked (by uuid) when
 * registering a book; the search response lists each author's books so homonyms
 * can be told apart.
 */
@RestController
@RequestMapping("/api/authors")
public class AuthorController {

    private final AuthorService authorService;

    public AuthorController(AuthorService authorService) {
        this.authorService = authorService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateAuthorResponse create(@Valid @RequestBody CreateAuthorRequest req) {
        return new CreateAuthorResponse(authorService.create(req.authorName()));
    }

    @GetMapping
    public List<AuthorSearchResponse> search(@RequestParam String name) {
        return authorService.searchByName(name).stream()
                .map(AuthorSearchResponse::from)
                .toList();
    }
}
