package com.example.bookserver.book;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
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
     *
     * <p>The ISBN is what makes a resubmitted form a 409 rather than a second copy of the same
     * book. The duplicate is caught from the unique index rather than by looking first: a check
     * and an insert in separate statements leave a window for two concurrent calls to both pass
     * the check, and the index does not have that window.
     *
     * @throws DuplicateIsbnException if the catalogue already holds this ISBN
     */
    @Transactional
    public UUID create(BookRequest req) {
        UUID bookUuid = Uuids.newId();
        Book book = new Book(bookUuid, req.isbn(), req.bookTitle(), req.bookDescription(),
                req.price(), req.publishDate(), req.publisher(), req.inventory(), null);
        try {
            bookMapper.insert(book);
        } catch (DuplicateKeyException e) {
            throw new DuplicateIsbnException(req.isbn());
        }
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

    /** A search page is fixed-size so callers cannot choose the response and database cost. */
    public static final int SEARCH_PAGE_SIZE = 20;

    private static final int NAVIGATION_PROBE_LIMIT =
            SEARCH_PAGE_SIZE * BookPage.NAVIGATION_PAGE_COUNT + 1;

    /**
     * One fixed-size page of books whose title contains {@code title}, case-insensitively,
     * newest first (bodies only, authors not fetched).
     *
     * <p>The navigation probe counts at most 101 matches starting at this page: enough to know
     * which of the next five page buttons exist and whether {@code >>} may move to the next
     * block. It deliberately does not count every matching book.
     *
     * @throws BlankSearchQueryException if the query is empty or only whitespace
     * @throws InvalidPageException if the page is negative
     */
    public BookPage search(String title, int page) {
        if (title == null || title.isBlank()) {
            throw new BlankSearchQueryException();
        }
        if (page < 0) {
            throw new InvalidPageException("page must be zero or greater");
        }

        String normalizedTitle = title.trim();
        long offset = (long) page * SEARCH_PAGE_SIZE;
        List<Book> content = bookMapper.searchByTitle(
                normalizedTitle, offset, SEARCH_PAGE_SIZE);
        int navigationElements = content.size() < SEARCH_PAGE_SIZE
                ? content.size()
                : bookMapper.countSearchWindow(
                        normalizedTitle, offset, NAVIGATION_PROBE_LIMIT);
        return new BookPage(content, page, SEARCH_PAGE_SIZE, navigationElements);
    }

    /**
     * Update the catalogue entry and replace the author links. Stock is not part of it — see
     * {@link #adjustStock} — so an edit can no longer put back copies sold while it was open.
     */
    @Transactional
    public void update(UUID bookUuid, UpdateBookRequest req) {
        requireBook(bookUuid);
        // isbn and inventory are both null on purpose: neither is a column this update writes.
        Book book = new Book(bookUuid, null, req.bookTitle(), req.bookDescription(),
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
