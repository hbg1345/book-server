package com.example.bookserver.book;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.book.dto.BookRequest;
import com.example.bookserver.book.dto.BookResponse;
import com.example.bookserver.book.dto.CreateBookResponse;

import jakarta.validation.Valid;

/**
 * Book CRUD endpoints. Reads are open; writes are not yet protected
 * (auth is introduced separately with the Spring Security migration).
 */
@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateBookResponse create(@Valid @RequestBody BookRequest req) {
        return new CreateBookResponse(bookService.create(req));
    }

    @GetMapping
    public List<BookResponse> list() {
        return bookService.list().stream().map(BookResponse::from).toList();
    }

    @GetMapping("/{bookUuid}")
    public BookResponse get(@PathVariable UUID bookUuid) {
        return BookResponse.from(bookService.get(bookUuid));
    }

    @PutMapping("/{bookUuid}")
    public void update(@PathVariable UUID bookUuid, @Valid @RequestBody BookRequest req) {
        bookService.update(bookUuid, req);
    }

    @DeleteMapping("/{bookUuid}")
    public void delete(@PathVariable UUID bookUuid) {
        bookService.delete(bookUuid);
    }
}
