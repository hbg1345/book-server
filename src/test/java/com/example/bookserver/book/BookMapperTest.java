package com.example.bookserver.book;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    // 3b. findAll — empty table, then all inserted book bodies
    // Verifies: findAll returns nothing on an empty table and every inserted
    // book afterwards (authors not fetched by the list query).
    @Test
    void findAll_returnsAllBooks() {
        assertThat(bookMapper.findAll()).isEmpty();

        UUID id1 = Uuids.newId();
        UUID id2 = Uuids.newId();
        bookMapper.insert(sampleBook(id1));
        bookMapper.insert(sampleBook(id2));

        assertThat(bookMapper.findAll())
                .extracting(Book::getBookUuid)
                .containsExactlyInAnyOrder(id1, id2);
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
    // Verifies: update writes every mutable column correctly — each field is
    // changed to a distinct value so a broken SET mapping cannot slip through.
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
        changed.setInventory(5);
        bookMapper.update(changed);

        Book found = bookMapper.findById(bookId);
        assertThat(found.getBookTitle()).isEqualTo("Clean Code");
        assertThat(found.getBookDescription()).isEqualTo("Updated description");
        assertThat(found.getPrice()).isEqualByComparingTo("49.99");
        assertThat(found.getPublishDate()).isEqualTo(LocalDate.of(2008, 8, 1));
        assertThat(found.getPublisher()).isEqualTo("Prentice Hall");
        assertThat(found.getInventory()).isEqualTo(5);
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

        assertThat(bookMapper.searchByTitle("clean", 50))
                .extracting(Book::getBookTitle)
                .containsExactlyInAnyOrder("Clean Code", "The Unclean Truth");
    }

    // Verifies: LIKE wildcards typed by the user are matched literally. Without escaping,
    // a search for "%" would return the whole catalogue and "100_" would match "1000",
    // so the query would answer a question nobody asked.
    @Test
    void searchByTitle_treatsWildcardsAsLiteralText() {
        insertTitled("Clean Code");
        insertTitled("100% Pure Java");
        insertTitled("1000 Ideas");

        assertThat(bookMapper.searchByTitle("%", 50))
                .extracting(Book::getBookTitle)
                .containsExactly("100% Pure Java");
        assertThat(bookMapper.searchByTitle("100_", 50)).isEmpty();
    }

    // Verifies: the limit is applied by the query rather than by the caller trimming the
    // result. The catalogue holds ~103k rows, so an unbounded LIKE scan would serialise
    // far more than any client asked for.
    @Test
    void searchByTitle_appliesLimit() {
        insertTitled("Clean Code");
        insertTitled("Clean Architecture");
        insertTitled("Clean Agile");

        assertThat(bookMapper.searchByTitle("Clean", 2)).hasSize(2);
    }

    // Verifies: no match is an empty list, not null — callers should not have to null-check.
    @Test
    void searchByTitle_returnsEmpty_whenNothingMatches() {
        insertTitled("Clean Code");

        assertThat(bookMapper.searchByTitle("Nonexistent", 50)).isEmpty();
    }
}
