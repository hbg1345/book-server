package com.example.bookserver.book;

import com.example.bookserver.Isbns;
import com.example.bookserver.TestcontainersConfiguration;
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

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
public class AuthorServiceTest {

    @Autowired
    private AuthorMapper authorMapper;
    @Autowired
    private BookMapper bookMapper;

    private AuthorService authorService;

    @BeforeEach
    void setUp() {
        authorService = new AuthorService(authorMapper);
    }

    private void insertBookByAuthor(String title, UUID authorUuid) {
        UUID bookUuid = Uuids.newId();
        bookMapper.insert(new Book(bookUuid, Isbns.next(), title, "desc",
                new BigDecimal("39.99"), LocalDate.of(2021, 1, 1), "Wikibooks", 10, null));
        bookMapper.linkAuthor(bookUuid, authorUuid);
    }

    // create persists the author and returns a fetchable uuid.
    @Test
    void create_persistsAuthor_andReturnsId() {
        UUID authorUuid = authorService.create("Robert Martin");

        Author found = authorMapper.findById(authorUuid);
        assertThat(found).isNotNull();
        assertThat(found.getAuthorName()).isEqualTo("Robert Martin");
    }

    // searchByName returns each homonym together with the titles of their own books,
    // so distinct authors sharing a name can be told apart.
    @Test
    void searchByName_returnsHomonymsWithTheirBooks() {
        UUID kim1 = authorService.create("Kim");
        UUID kim2 = authorService.create("Kim");
        insertBookByAuthor("Refactoring", kim1);
        insertBookByAuthor("Clean Code", kim1);
        insertBookByAuthor("Java Basics", kim2);

        List<AuthorSearchResult> results = authorService.searchByName("Kim");

        assertThat(results).hasSize(2);
        AuthorSearchResult r1 = results.stream()
                .filter(r -> r.author().getAuthorUuid().equals(kim1)).findFirst().orElseThrow();
        AuthorSearchResult r2 = results.stream()
                .filter(r -> r.author().getAuthorUuid().equals(kim2)).findFirst().orElseThrow();
        assertThat(r1.bookTitles()).containsExactlyInAnyOrder("Refactoring", "Clean Code");
        assertThat(r2.bookTitles()).containsExactly("Java Basics");
    }

    // searchByName returns nothing for an unknown name.
    @Test
    void searchByName_returnsEmpty_whenNoMatch() {
        assertThat(authorService.searchByName("Nobody")).isEmpty();
    }
}
