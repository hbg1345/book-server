package com.example.bookserver.book;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.bookserver.book.dto.BookRequest;
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
        insertRejectingDuplicate(book);
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

    /** All books (bodies only, authors not fetched), newest first. */
    public List<Book> list() {
        return bookMapper.findAll();
    }

    /** Update every mutable field and replace the author links. */
    @Transactional
    public void update(UUID bookUuid, BookRequest req) {
        requireBook(bookUuid);
        Book book = new Book(bookUuid, req.bookTitle(), req.bookDescription(),
                req.price(), req.publishDate(), req.publisher(), req.inventory(), null);
        updateRejectingDuplicate(book);
        bookMapper.unlinkAuthors(bookUuid);
        linkAuthors(bookUuid, req.authorUuids());
    }

    /** Delete a book (its author links and cart/history rows cascade via their FKs). */
    public void delete(UUID bookUuid) {
        requireBook(bookUuid);
        bookMapper.delete(bookUuid);
    }

    /**
     * The catalogue keys a book on (title, publisher, publish date) — see V10. The insert is what
     * enforces it: checking first and then inserting would leave a window for a second submission
     * to pass the same check, which is exactly the double-click this guards against. So the
     * violation is translated rather than prevented.
     */
    private void insertRejectingDuplicate(Book book) {
        try {
            bookMapper.insert(book);
        } catch (DuplicateKeyException e) {
            throw new DuplicateBookException(book.getBookTitle());
        }
    }

    /** An edit can collide with an existing book just as a registration can. */
    private void updateRejectingDuplicate(Book book) {
        try {
            bookMapper.update(book);
        } catch (DuplicateKeyException e) {
            throw new DuplicateBookException(book.getBookTitle());
        }
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
