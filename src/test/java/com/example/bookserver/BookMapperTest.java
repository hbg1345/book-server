package com.example.bookserver;

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
@Sql("/schema.sql")
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
    @Test
    void insert_and_findBook() {
        UUID bookId = UUID.randomUUID();
        bookMapper.insert(sampleBook(bookId));

        Book found = bookMapper.findById(bookId);

        assertThat(found).isNotNull();
        assertThat(found.getBookTitle()).isEqualTo("Clean Architecture");
        assertThat(found.getPrice()).isEqualByComparingTo("39.99");
        assertThat(found.getInventory()).isEqualTo(10);
        assertThat(found.getAuthors()).isNull();   // findById does not fetch authors
    }

    // 2. the M:N relation on its own
    @Test
    void linkAuthor_and_findAuthorsByBookId() {
        UUID bookId = UUID.randomUUID();
        Author a1 = new Author(UUID.randomUUID(), "Robert Martin");
        Author a2 = new Author(UUID.randomUUID(), "John Doe");

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
    @Test
    void findByIdWithAuthors_returnsBookAndAuthors() {
        UUID bookId = UUID.randomUUID();
        Author a1 = new Author(UUID.randomUUID(), "Robert Martin");
        Author a2 = new Author(UUID.randomUUID(), "John Doe");

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

    // 4. update
    @Test
    void update() {
        UUID bookId = UUID.randomUUID();
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
    @Test
    void delete() {
        UUID bookId = UUID.randomUUID();
        bookMapper.insert(sampleBook(bookId));

        bookMapper.delete(bookId);

        assertThat(bookMapper.findById(bookId)).isNull();
    }
}
