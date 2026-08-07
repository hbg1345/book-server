package com.example.bookserver.book;

import com.example.bookserver.Isbns;
import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
public class AuthorMapperTest {

    @Autowired
    private AuthorMapper authorMapper;
    @Autowired
    private BookMapper bookMapper;

    // --- helper: a minimal book linked to the given author ---
    private UUID insertBookByAuthor(String title, UUID authorUuid) {
        UUID bookUuid = Uuids.newId();
        Book book = new Book(bookUuid, Isbns.next(), title, "desc",
                new BigDecimal("39.99"), LocalDate.of(2021, 1, 1), "Wikibooks", 10, null);
        bookMapper.insert(book);
        bookMapper.linkAuthor(bookUuid, authorUuid);
        return bookUuid;
    }

    // Verifies: findById returns null when no author with that id exists.
    @Test
    void findById_returnsNull_whenNotExists() {
        Author found = authorMapper.findById(Uuids.newId());
        assertThat(found).isNull();
    }

    // Verifies: an inserted author can be read back by id with its fields intact.
    @Test
    void findById() {
        UUID authorUuid = Uuids.newId();
        String authorName = "Jane Doe";
        Author author = new Author(authorUuid, authorName);

        authorMapper.insert(author);
        Author found = authorMapper.findById(authorUuid);

        assertThat(found).isNotNull();
        assertThat(found.getAuthorUuid()).isEqualTo(authorUuid);
        assertThat(found.getAuthorName()).isEqualTo(authorName);
    }

    // Verifies: update changes the author's name and the new value persists.
    @Test
    void update() {
        UUID authorUuid = Uuids.newId();
        authorMapper.insert(new Author(authorUuid, "Jane Doe"));

        authorMapper.update(new Author(authorUuid, "Jane Smith"));

        Author found = authorMapper.findById(authorUuid);
        assertThat(found.getAuthorName()).isEqualTo("Jane Smith");
    }

    // Verifies: findByName returns every author with that exact name (homonyms
    // included, since author_name is not unique), and nothing for an unknown name.
    @Test
    void findByName_returnsAllHomonyms() {
        UUID id1 = Uuids.newId();
        UUID id2 = Uuids.newId();
        authorMapper.insert(new Author(id1, "Kim"));
        authorMapper.insert(new Author(id2, "Kim"));      // same name, different author
        authorMapper.insert(new Author(Uuids.newId(), "Lee"));

        assertThat(authorMapper.findByName("Kim"))
                .extracting(Author::getAuthorUuid)
                .containsExactlyInAnyOrder(id1, id2);
        assertThat(authorMapper.findByName("Nobody")).isEmpty();
    }

    // Verifies: findBookTitlesByAuthorId returns the titles of books linked to the
    // author, and only those (a book by another author is not included).
    @Test
    void findBookTitlesByAuthorId_returnsOnlyThatAuthorsBooks() {
        UUID author = Uuids.newId();
        UUID other = Uuids.newId();
        authorMapper.insert(new Author(author, "Robert Martin"));
        authorMapper.insert(new Author(other, "Someone Else"));

        insertBookByAuthor("Clean Code", author);
        insertBookByAuthor("Clean Architecture", author);
        insertBookByAuthor("Unrelated Book", other);

        assertThat(authorMapper.findBookTitlesByAuthorId(author))
                .containsExactlyInAnyOrder("Clean Code", "Clean Architecture");
        assertThat(authorMapper.findBookTitlesByAuthorId(Uuids.newId())).isEmpty();
    }

    // Verifies: delete removes the author so it can no longer be found.
    @Test
    void delete() {
        UUID authorUuid = Uuids.newId();
        authorMapper.insert(new Author(authorUuid, "Jane Doe"));

        authorMapper.delete(authorUuid);

        assertThat(authorMapper.findById(authorUuid)).isNull();
    }
}
