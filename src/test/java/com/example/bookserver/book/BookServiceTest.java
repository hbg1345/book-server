package com.example.bookserver.book;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.book.dto.BookRequest;
import com.example.bookserver.common.Uuids;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
public class BookServiceTest {

    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private AuthorMapper authorMapper;

    private BookService bookService;

    @BeforeEach
    void setUp() {
        bookService = new BookService(bookMapper);
    }

    private BookRequest sampleRequest(List<UUID> authorUuids) {
        return new BookRequest("Clean Architecture", "A book about software architecture",
                new BigDecimal("39.99"), LocalDate.of(2021, 1, 1), "Wikibooks", 10, authorUuids);
    }

    // create persists the book body and returns a fetchable uuid.
    @Test
    void create_persistsBook_andReturnsId() {
        UUID bookUuid = bookService.create(sampleRequest(null));

        Book found = bookMapper.findById(bookUuid);
        assertThat(found).isNotNull();
        assertThat(found.getBookTitle()).isEqualTo("Clean Architecture");
        assertThat(found.getPrice()).isEqualByComparingTo("39.99");
        assertThat(found.getInventory()).isEqualTo(10);
    }

    // create with authorUuids links them; get returns the book with those authors.
    @Test
    void create_linksAuthors_andGetReturnsThem() {
        Author a1 = new Author(Uuids.newId(), "Robert Martin");
        Author a2 = new Author(Uuids.newId(), "John Doe");
        authorMapper.insert(a1);
        authorMapper.insert(a2);

        UUID bookUuid = bookService.create(
                sampleRequest(List.of(a1.getAuthorUuid(), a2.getAuthorUuid())));

        Book found = bookService.get(bookUuid);
        assertThat(found.getAuthors())
                .extracting(Author::getAuthorName)
                .containsExactlyInAnyOrder("Robert Martin", "John Doe");
    }

    // get on a missing id throws.
    @Test
    void get_throws_whenAbsent() {
        assertThatThrownBy(() -> bookService.get(Uuids.newId()))
                .isInstanceOf(BookNotFoundException.class);
    }

    // list returns every created book.
    @Test
    void list_returnsAllBooks() {
        bookService.create(sampleRequest(null));
        bookService.create(sampleRequest(null));

        assertThat(bookService.list()).hasSize(2);
    }

    // update rewrites every mutable field and replaces the author links.
    @Test
    void update_changesFields_andReplacesAuthors() {
        Author oldAuthor = new Author(Uuids.newId(), "Old Author");
        Author newAuthor = new Author(Uuids.newId(), "New Author");
        authorMapper.insert(oldAuthor);
        authorMapper.insert(newAuthor);

        UUID bookUuid = bookService.create(sampleRequest(List.of(oldAuthor.getAuthorUuid())));

        BookRequest changed = new BookRequest("Clean Code", "Updated description",
                new BigDecimal("49.99"), LocalDate.of(2008, 8, 1), "Prentice Hall", 5,
                List.of(newAuthor.getAuthorUuid()));
        bookService.update(bookUuid, changed);

        Book found = bookService.get(bookUuid);
        assertThat(found.getBookTitle()).isEqualTo("Clean Code");
        assertThat(found.getBookDescription()).isEqualTo("Updated description");
        assertThat(found.getPrice()).isEqualByComparingTo("49.99");
        assertThat(found.getPublishDate()).isEqualTo(LocalDate.of(2008, 8, 1));
        assertThat(found.getPublisher()).isEqualTo("Prentice Hall");
        assertThat(found.getInventory()).isEqualTo(5);
        assertThat(found.getAuthors())
                .extracting(Author::getAuthorName)
                .containsExactly("New Author");   // old link replaced, not appended
    }

    // update on a missing id throws.
    @Test
    void update_throws_whenAbsent() {
        assertThatThrownBy(() -> bookService.update(Uuids.newId(), sampleRequest(null)))
                .isInstanceOf(BookNotFoundException.class);
    }

    // delete removes the book; deleting a missing book throws.
    @Test
    void delete_removesBook_orThrowsWhenAbsent() {
        UUID bookUuid = bookService.create(sampleRequest(null));

        bookService.delete(bookUuid);
        assertThat(bookMapper.findById(bookUuid)).isNull();

        assertThatThrownBy(() -> bookService.delete(Uuids.newId()))
                .isInstanceOf(BookNotFoundException.class);
    }

    // A book is identified by (title, publisher, publish date). Registering the same one twice is
    // reported rather than quietly listed twice — a second row brings its own inventory, so the
    // catalogue would offer stock the shop does not have.
    @Test
    void create_throws_whenTheBookIsAlreadyInTheCatalogue() {
        bookService.create(sampleRequest(null));

        assertThatThrownBy(() -> bookService.create(sampleRequest(null)))
                .isInstanceOf(DuplicateBookException.class);
    }

    // A different publish date is a different book, even under the same title and publisher.
    @Test
    void create_allowsTheSameTitleWithADifferentIdentity() {
        bookService.create(sampleRequest(null));

        UUID other = bookService.create(new BookRequest("Clean Architecture", "reissue",
                new BigDecimal("39.99"), LocalDate.of(2023, 5, 1), "Wikibooks", 10, null));

        assertThat(bookMapper.findById(other)).isNotNull();
    }

    // Editing a book onto an identity another book already holds is refused the same way.
    @Test
    void update_throws_whenItCollidesWithAnotherBook() {
        UUID first = bookService.create(sampleRequest(null));
        UUID second = bookService.create(new BookRequest("Refactoring", "desc",
                new BigDecimal("39.99"), LocalDate.of(2021, 1, 1), "Wikibooks", 10, null));

        assertThatThrownBy(() -> bookService.update(second, sampleRequest(null)))
                .isInstanceOf(DuplicateBookException.class);
        assertThat(bookMapper.findById(first).getBookTitle()).isEqualTo("Clean Architecture");
    }
}
