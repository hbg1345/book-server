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

    @Test
    void save_and_findById() {
        // given: two authors and one book
        UUID bookId = UUID.randomUUID();
        Author a1 = new Author(UUID.randomUUID(), "Robert Martin");
        Author a2 = new Author(UUID.randomUUID(), "John Doe");

        Book book = new Book();
        book.setBookUuid(bookId);
        book.setBookTitle("Clean Architecture");
        book.setBookDescription("A book about software architecture");
        book.setPrice(new BigDecimal("39.99"));
        book.setPublishDate(LocalDate.of(2021, 1, 1));
        book.setPublisher("Wikibooks");
        book.setInventory(10);

        // when: save authors -> book -> links (split because of the M:N relation)
        authorMapper.insert(a1);
        authorMapper.insert(a2);
        bookMapper.insert(book);
        bookMapper.linkAuthor(bookId, a1.getAuthorUuid());
        bookMapper.linkAuthor(bookId, a2.getAuthorUuid());

        // then: the book plus its two authors come back as a list
        Book found = bookMapper.findById(bookId);

        assertThat(found.getBookTitle()).isEqualTo("Clean Architecture");
        assertThat(found.getPrice()).isEqualByComparingTo("39.99");
        assertThat(found.getInventory()).isEqualTo(10);
        assertThat(found.getAuthors()).hasSize(2);
        assertThat(found.getAuthors())
                .extracting(Author::getAuthorName)
                .containsExactlyInAnyOrder("Robert Martin", "John Doe");
    }
}
