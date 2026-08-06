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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.book.dto.BookPageResponse;
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

    /**
     * The catalogue, one page at a time, optionally filtered. {@code ?title=} narrows the same
     * collection resource rather than introducing a /search sub-resource: the thing being
     * addressed is still the set of books, and a filtered set is not a different kind of thing.
     *
     * <p>The response shape does not change with the parameters — a client that asked for a
     * page and a client that asked for a search parse the same thing. A search is served as a
     * single page: it is capped rather than paged (see {@link BookService#SEARCH_LIMIT}), so
     * its total is the hits returned and there is never a second page to fetch.
     */
    @GetMapping
    public BookPageResponse list(@RequestParam(required = false) String title,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "" + BookService.DEFAULT_SIZE) int size) {
        if (title != null) {
            List<Book> hits = bookService.search(title);
            return BookPageResponse.from(new BookPage(hits, 0, Math.max(hits.size(), 1), hits.size()));
        }
        return BookPageResponse.from(bookService.list(page, size));
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
