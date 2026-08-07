package com.example.bookserver.book;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.bookserver.book.dto.BookRequest;
import com.example.bookserver.book.dto.UpdateBookRequest;
import com.example.bookserver.common.Uuids;

@Service
public class BookService {

    private final BookMapper bookMapper;

    public BookService(BookMapper bookMapper) {
        this.bookMapper = bookMapper;
    }

    /**
     * Create a book and link the given (already-existing) authors. Returns the
     * generated book_uuid. Insert + author links are one transaction.
     */
    @Transactional
    public UUID create(BookRequest req) {
        UUID bookUuid = Uuids.newId();
        Book book = new Book(bookUuid, req.bookTitle(), req.bookDescription(),
                req.price(), req.publishDate(), req.publisher(), req.inventory(), null);
        bookMapper.insert(book);
        linkAuthors(bookUuid, req.authorUuids());
        return bookUuid;
    }

    /** A single book with its authors. */
    public Book get(UUID bookUuid) {
        Book book = bookMapper.findByIdWithAuthors(bookUuid);
        if (book == null) {
            throw new BookNotFoundException(bookUuid);
        }
        return book;
    }

    /** Books per page when the client does not say. Twenty is a screenful, not a download. */
    public static final int DEFAULT_SIZE = 20;

    /**
     * The largest page a client may ask for. A size is a promise about response bytes, and
     * without a ceiling one request can ask for the whole 103k-row catalogue by naming it.
     */
    public static final int MAX_SIZE = 100;

    /**
     * The deepest page the catalogue will serve, counted from zero.
     *
     * <p>OFFSET does not skip rows, it reads and discards them, so page 5000 costs a hundred
     * thousand rows to answer — the most expensive requests in the endpoint, and the ones no
     * shopper makes. Real catalogues cap in the same place; past a hundred pages the traffic is
     * crawlers. Anyone who genuinely wants something deeper wants a search, not page 5000.
     */
    public static final int MAX_PAGE = 99;

    /**
     * One page of books (bodies only, authors not fetched), newest first.
     *
     * @throws InvalidPageException if the page or size is outside what the catalogue serves
     */
    public BookPage list(int page, int size) {
        if (page < 0) {
            throw new InvalidPageException("page must not be negative: " + page);
        }
        if (page > MAX_PAGE) {
            throw new InvalidPageException(
                    "page must be at most " + MAX_PAGE + "; narrow the results instead: " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidPageException("size must be between 1 and " + MAX_SIZE + ": " + size);
        }
        return new BookPage(bookMapper.findPage(size, page * size), page, size, bookMapper.countAll());
    }

    /**
     * How many hits a search may return. Fixed here rather than accepted from the caller:
     * a client-supplied limit is a client-supplied cost, and the predicate scans the whole
     * catalogue. Paging belongs with the pagination this endpoint does not have yet.
     */
    public static final int SEARCH_LIMIT = 50;

    /**
     * Books whose title contains {@code title}, case-insensitively, newest first
     * (bodies only, authors not fetched — same shape as {@link #list()}).
     *
     * @throws BlankSearchQueryException if the query is empty or only whitespace
     */
    public List<Book> search(String title) {
        if (title == null || title.isBlank()) {
            throw new BlankSearchQueryException();
        }
        return bookMapper.searchByTitle(title.trim(), SEARCH_LIMIT);
    }

    /**
     * Update the catalogue entry and replace the author links. Stock is not part of it — see
     * {@link #adjustStock} — so an edit can no longer put back copies sold while it was open.
     */
    @Transactional
    public void update(UUID bookUuid, UpdateBookRequest req) {
        requireBook(bookUuid);
        Book book = new Book(bookUuid, req.bookTitle(), req.bookDescription(),
                req.price(), req.publishDate(), req.publisher(), null, null);
        bookMapper.update(book);
        bookMapper.unlinkAuthors(bookUuid);
        linkAuthors(bookUuid, req.authorUuids());
    }

    /**
     * Move a book's stock by {@code delta} and return what it holds afterwards.
     *
     * <p>A change rather than a total, so nothing has to be read first and nothing can be
     * written back stale. The floor is enforced by the same statement that applies the change,
     * which is what stops a write-off and a sale from both passing a check that only one of
     * them can still satisfy.
     *
     * <p>What a delta gives up is idempotency: sending "+20" twice receives forty copies. There
     * is nothing here that recognises the second request as a repeat of the first, so a
     * double-clicked button or a retried request applies twice. Guarding that needs an identity
     * for the movement, which nothing in a bare {@code {"delta": 20}} supplies — the frontend
     * is what stops the repeat reaching us.
     *
     * @throws InsufficientStockException if the change would leave fewer than zero copies
     */
    @Transactional
    public int adjustStock(UUID bookUuid, int delta) {
        requireBook(bookUuid);
        if (bookMapper.adjustInventory(bookUuid, delta) == 0) {
            throw new InsufficientStockException(bookUuid, delta);
        }
        return bookMapper.findById(bookUuid).getInventory();
    }

    /** Delete a book (its author links and cart/history rows cascade via their FKs). */
    public void delete(UUID bookUuid) {
        requireBook(bookUuid);
        bookMapper.delete(bookUuid);
    }

    private void linkAuthors(UUID bookUuid, List<UUID> authorUuids) {
        if (authorUuids == null) {
            return;
        }
        for (UUID authorUuid : authorUuids) {
            bookMapper.linkAuthor(bookUuid, authorUuid);
        }
    }

    private void requireBook(UUID bookUuid) {
        if (bookMapper.findById(bookUuid) == null) {
            throw new BookNotFoundException(bookUuid);
        }
    }
}
