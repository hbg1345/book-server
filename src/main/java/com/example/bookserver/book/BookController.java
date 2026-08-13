package com.example.bookserver.book;

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

import com.example.bookserver.book.dto.AdjustStockRequest;
import com.example.bookserver.book.dto.BookPageResponse;
import com.example.bookserver.book.dto.BookRequest;
import com.example.bookserver.book.dto.BookResponse;
import com.example.bookserver.book.dto.CreateBookResponse;
import com.example.bookserver.book.dto.StockResponse;
import com.example.bookserver.book.dto.UpdateBookRequest;

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
     * Search the catalogue by title. An unfiltered read is deliberately not exposed: readers
     * discover books through search and then open a detail, rather than walking the entire
     * catalogue. Results are returned in fixed-size pages; the client cannot increase the
     * response cost by supplying its own page size.
     */
    @GetMapping
    public BookPageResponse search(@RequestParam String title,
                                   @RequestParam(defaultValue = "0") int page) {
        return BookPageResponse.from(bookService.search(title, page));
    }

    @GetMapping("/{bookUuid}")
    public BookResponse get(@PathVariable UUID bookUuid) {
        return BookResponse.from(bookService.get(bookUuid));
    }

    @PutMapping("/{bookUuid}")
    public void update(@PathVariable UUID bookUuid, @Valid @RequestBody UpdateBookRequest req) {
        bookService.update(bookUuid, req);
    }

    /**
     * Move stock: {@code {"delta": 20}} receives copies, {@code {"delta": -2}} writes them off.
     *
     * <p>Separate from the update above because it is a separate act. Editing the catalogue and
     * changing what the shop holds are done by different people for different reasons, and
     * bundling them meant an editor could not touch one without restating the other from a
     * figure that had since moved on.
     *
     * <p>A delta and not a total, so the caller never needs to know the current count — the one
     * thing it cannot know reliably while the book is selling.
     *
     * <p>Not idempotent: two identical requests move the stock twice, because nothing in them
     * says whether that is a repeat or a second genuine receipt. The frontend must not send the
     * second one.
     */
    @PostMapping("/{bookUuid}/stock")
    public StockResponse adjustStock(@PathVariable UUID bookUuid,
                                     @Valid @RequestBody AdjustStockRequest req) {
        return new StockResponse(bookService.adjustStock(bookUuid, req.delta()));
    }

    @DeleteMapping("/{bookUuid}")
    public void delete(@PathVariable UUID bookUuid) {
        bookService.delete(bookUuid);
    }
}
