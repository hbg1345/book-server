package com.example.bookserver.book;

import com.example.bookserver.Isbns;
import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
public class BookMapperTest {

    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private AuthorMapper authorMapper;

    // --- helper: a fully-populated book (no authors) ---
    private Book sampleBook(UUID bookId) {
        Book book = new Book();
        book.setBookUuid(bookId);
        book.setIsbn(Isbns.next());
        book.setBookTitle("Clean Architecture");
        book.setBookDescription("A book about software architecture");
        book.setPrice(new BigDecimal("39.99"));
        book.setPublishDate(LocalDate.of(2021, 1, 1));
        book.setPublisher("Wikibooks");
        book.setInventory(10);
        return book;
    }

    // 1. own table only — no relation involved
    // Verifies: an inserted book round-trips its own columns, and findById
    // (the no-authors variant) leaves the authors field null.
    @Test
    void insert_and_findBook() {
        UUID bookId = Uuids.newId();
        bookMapper.insert(sampleBook(bookId));

        Book found = bookMapper.findById(bookId);

        assertThat(found).isNotNull();
        assertThat(found.getBookTitle()).isEqualTo("Clean Architecture");
        assertThat(found.getPrice()).isEqualByComparingTo("39.99");
        assertThat(found.getInventory()).isEqualTo(10);
        assertThat(found.getAuthors()).isNull();   // findById does not fetch authors
    }

    // Verifies: decrementInventory reserves stock while enough remains (returns 1
    // and lowers the count), and refuses once the request exceeds stock (returns 0,
    // leaves inventory untouched) — the atomic out-of-stock guard.
    @Test
    void decrementInventory_reservesWhileEnough_thenRefuses() {
        UUID bookId = Uuids.newId();
        bookMapper.insert(sampleBook(bookId));   // inventory 10

        assertThat(bookMapper.decrementInventory(bookId, 4)).isEqualTo(1);
        assertThat(bookMapper.findById(bookId).getInventory()).isEqualTo(6);

        // asking for more than the remaining 6 is refused, nothing changes
        assertThat(bookMapper.decrementInventory(bookId, 7)).isZero();
        assertThat(bookMapper.findById(bookId).getInventory()).isEqualTo(6);
    }

    // Verifies: incrementInventory gives stock back (e.g. on cancel).
    @Test
    void incrementInventory_addsStockBack() {
        UUID bookId = Uuids.newId();
        bookMapper.insert(sampleBook(bookId));   // inventory 10

        bookMapper.incrementInventory(bookId, 3);

        assertThat(bookMapper.findById(bookId).getInventory()).isEqualTo(13);
    }

    // Verifies: adjustInventory moves stock in either direction and refuses to go
    // below zero (returns 0, leaves inventory untouched). Relative and conditional
    // like decrementInventory, so an admin's adjustment cannot erase a concurrent sale.
    @Test
    void adjustInventory_movesEitherWay_andRefusesToGoNegative() {
        UUID bookId = Uuids.newId();
        bookMapper.insert(sampleBook(bookId));   // inventory 10

        assertThat(bookMapper.adjustInventory(bookId, 20)).isEqualTo(1);    // received
        assertThat(bookMapper.findById(bookId).getInventory()).isEqualTo(30);

        assertThat(bookMapper.adjustInventory(bookId, -2)).isEqualTo(1);    // written off
        assertThat(bookMapper.findById(bookId).getInventory()).isEqualTo(28);

        // emptying the shelf exactly is fine; one copy further is not
        assertThat(bookMapper.adjustInventory(bookId, -28)).isEqualTo(1);
        assertThat(bookMapper.adjustInventory(bookId, -1)).isZero();
        assertThat(bookMapper.findById(bookId).getInventory()).isZero();
    }

    // 2. the M:N relation on its own
    // Verifies: after linking authors to a book, findAuthorsByBookId returns
    // exactly those authors (the join table + join query work).
    @Test
    void linkAuthor_and_findAuthorsByBookId() {
        UUID bookId = Uuids.newId();
        Author a1 = new Author(Uuids.newId(), "Robert Martin");
        Author a2 = new Author(Uuids.newId(), "John Doe");

        // FK requires both sides to exist before linking
        authorMapper.insert(a1);
        authorMapper.insert(a2);
        bookMapper.insert(sampleBook(bookId));
        bookMapper.linkAuthor(bookId, a1.getAuthorUuid());
        bookMapper.linkAuthor(bookId, a2.getAuthorUuid());

        assertThat(bookMapper.findAuthorsByBookId(bookId))
                .extracting(Author::getAuthorName)
                .containsExactlyInAnyOrder("Robert Martin", "John Doe");
    }

    // 3. integration happy-path — book + nested authors assembled by @Many
    // Verifies: findByIdWithAuthors assembles the book together with its
    // nested authors list via the @Many mapping.
    @Test
    void findByIdWithAuthors_returnsBookAndAuthors() {
        UUID bookId = Uuids.newId();
        Author a1 = new Author(Uuids.newId(), "Robert Martin");
        Author a2 = new Author(Uuids.newId(), "John Doe");

        authorMapper.insert(a1);
        authorMapper.insert(a2);
        bookMapper.insert(sampleBook(bookId));
        bookMapper.linkAuthor(bookId, a1.getAuthorUuid());
        bookMapper.linkAuthor(bookId, a2.getAuthorUuid());

        Book found = bookMapper.findByIdWithAuthors(bookId);

        assertThat(found.getAuthors()).hasSize(2);
        assertThat(found.getAuthors())
                .extracting(Author::getAuthorName)
                .containsExactlyInAnyOrder("Robert Martin", "John Doe");
    }

    // 3c. unlinkAuthors — removes the join rows only
    // Verifies: unlinkAuthors drops every author link for the book while leaving
    // the book itself (and the author rows) intact.
    @Test
    void unlinkAuthors_removesLinksOnly() {
        UUID bookId = Uuids.newId();
        Author a1 = new Author(Uuids.newId(), "Robert Martin");
        Author a2 = new Author(Uuids.newId(), "John Doe");
        authorMapper.insert(a1);
        authorMapper.insert(a2);
        bookMapper.insert(sampleBook(bookId));
        bookMapper.linkAuthor(bookId, a1.getAuthorUuid());
        bookMapper.linkAuthor(bookId, a2.getAuthorUuid());

        bookMapper.unlinkAuthors(bookId);

        assertThat(bookMapper.findAuthorsByBookId(bookId)).isEmpty();
        assertThat(bookMapper.findById(bookId)).isNotNull();             // book kept
        assertThat(authorMapper.findById(a1.getAuthorUuid())).isNotNull(); // author kept
    }

    // 4. update
    // Verifies: update writes every catalogue column correctly — each field is
    // changed to a distinct value so a broken SET mapping cannot slip through —
    // and leaves inventory alone even when the Book handed to it carries one.
    @Test
    void update() {
        UUID bookId = Uuids.newId();
        bookMapper.insert(sampleBook(bookId));

        // change every mutable field to a distinct value so a broken
        // column mapping in the UPDATE cannot slip through
        Book changed = sampleBook(bookId);
        changed.setBookTitle("Clean Code");
        changed.setBookDescription("Updated description");
        changed.setPrice(new BigDecimal("49.99"));
        changed.setPublishDate(LocalDate.of(2008, 8, 1));
        changed.setPublisher("Prentice Hall");
        changed.setInventory(5);      // ignored: not a column this statement writes
        bookMapper.update(changed);

        Book found = bookMapper.findById(bookId);
        assertThat(found.getBookTitle()).isEqualTo("Clean Code");
        assertThat(found.getBookDescription()).isEqualTo("Updated description");
        assertThat(found.getPrice()).isEqualByComparingTo("49.99");
        assertThat(found.getPublishDate()).isEqualTo(LocalDate.of(2008, 8, 1));
        assertThat(found.getPublisher()).isEqualTo("Prentice Hall");
        assertThat(found.getInventory())
                .as("stock is not the editor's to restate; only adjustInventory moves it")
                .isEqualTo(10);
    }

    // 5. delete
    // Verifies: delete removes the book so it can no longer be found.
    @Test
    void delete() {
        UUID bookId = Uuids.newId();
        bookMapper.insert(sampleBook(bookId));

        bookMapper.delete(bookId);

        assertThat(bookMapper.findById(bookId)).isNull();
    }

    // --- helper: a book that exists only to carry a title ---
    private UUID insertTitled(String title) {
        UUID bookId = Uuids.newId();
        Book book = sampleBook(bookId);
        book.setBookTitle(title);
        bookMapper.insert(book);
        return bookId;
    }

    // 6. title search
    // Verifies: the match is on a substring anywhere in the title, not just the start,
    // and ignores case — someone typing "clean" expects both the book that starts with
    // it and the one that merely contains it.
    @Test
    void searchByTitle_matchesSubstring_ignoringCase() {
        insertTitled("Clean Code");
        insertTitled("The Unclean Truth");
        insertTitled("Refactoring");

        assertThat(bookMapper.searchByTitle("clean", 0, 50))
                .extracting(Book::getBookTitle)
                .containsExactlyInAnyOrder("Clean Code", "The Unclean Truth");
    }

    // A complete title is stronger intent than a newer title which only contains it. The
    // partial matches are inserted later so UUID-descending order would fail this assertion.
    @Test
    void searchByTitle_ranksAnExactTitleAheadOfNewerPartialMatches() {
        insertTitled("Clean Code");
        insertTitled("Clean Code Handbook");
        insertTitled("Writing Clean Code Every Day");

        assertThat(bookMapper.searchByTitle("Clean Code", 0, 50))
                .extracting(Book::getBookTitle)
                .first()
                .isEqualTo("Clean Code");
    }

    // ILIKE alone cannot return this title because the misspelled text is not a substring.
    @Test
    void searchByTitle_recoversAOneCharacterTypo() {
        insertTitled("Effective Java");
        insertTitled("Java Performance");

        assertThat(bookMapper.searchByTitle("Efective Java", 0, 50))
                .extracting(Book::getBookTitle)
                .containsExactly("Effective Java");
    }

    // A user need not reproduce punctuation printed on a cover to recover the book.
    @Test
    void searchByTitle_recoversPunctuationNormalization() {
        insertTitled("Domain-Driven Design");

        assertThat(bookMapper.searchByTitle("Domain Driven Design", 0, 50))
                .extracting(Book::getBookTitle)
                .containsExactly("Domain-Driven Design");
    }

    // Very short fuzzy terms are noisy and generate broad candidate sets. Literal matching
    // remains available, but a different two-character title is not treated as a typo.
    @Test
    void searchByTitle_disablesFuzzyMatchingForShortQueries() {
        insertTitled("AI");

        assertThat(bookMapper.searchByTitle("AI", 0, 50))
                .extracting(Book::getBookTitle)
                .containsExactly("AI");
        assertThat(bookMapper.searchByTitle("AJ", 0, 50)).isEmpty();
    }

    // Verifies: LIKE wildcards typed by the user are matched literally. Without escaping,
    // a search for "%" would return the whole catalogue and "100_" would match "1000",
    // so the query would answer a question nobody asked.
    @Test
    void searchByTitle_treatsWildcardsAsLiteralText() {
        insertTitled("Clean Code");
        insertTitled("100% Pure Java");
        insertTitled("1000 Ideas");

        assertThat(bookMapper.searchByTitle("%", 0, 50))
                .extracting(Book::getBookTitle)
                .containsExactly("100% Pure Java");
        assertThat(bookMapper.searchByTitle("100_", 0, 50)).isEmpty();
    }

    // Verifies: the limit is applied by the query rather than by the caller trimming the
    // result. The catalogue holds ~103k rows, so an unbounded LIKE scan would serialise
    // far more than any client asked for.
    @Test
    void searchByTitle_appliesLimit() {
        insertTitled("Clean Code");
        insertTitled("Clean Architecture");
        insertTitled("Clean Agile");

        assertThat(bookMapper.searchByTitle("Clean", 0, 2)).hasSize(2);
    }

    // Verifies: no match is an empty list, not null — callers should not have to null-check.
    @Test
    void searchByTitle_returnsEmpty_whenNothingMatches() {
        insertTitled("Clean Code");

        assertThat(bookMapper.searchByTitle("Nonexistent", 0, 50)).isEmpty();
    }

    // OFFSET selects a later result page without changing the fixed result order.
    @Test
    void searchByTitle_appliesOffset() {
        insertTitled("Clean Code");
        insertTitled("Clean Architecture");
        insertTitled("Clean Agile");

        List<Book> firstTwo = bookMapper.searchByTitle("Clean", 0, 2);
        List<Book> third = bookMapper.searchByTitle("Clean", 2, 2);

        assertThat(firstTwo).hasSize(2);
        assertThat(third).hasSize(1);
        assertThat(third.get(0).getBookUuid())
                .isNotIn(firstTwo.stream().map(Book::getBookUuid).toList());
    }

    // The navigation query counts only its bounded window, never every matching row.
    @Test
    void countSearchWindow_stopsAtTheLimit() {
        for (int i = 0; i < 7; i++) {
            insertTitled("Clean Code volume " + i);
        }

        assertThat(bookMapper.countSearchWindow("Clean", 0, 5)).isEqualTo(5);
        assertThat(bookMapper.countSearchWindow("Clean", 5, 5)).isEqualTo(2);
    }

}
