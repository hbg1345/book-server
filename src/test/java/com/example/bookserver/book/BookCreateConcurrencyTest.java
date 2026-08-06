package com.example.bookserver.book;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import com.example.bookserver.Concurrently;
import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.book.dto.BookRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reason the identity is a database constraint rather than a lookup in {@link BookService}.
 *
 * <p>Checking "is this book already listed?" and then inserting leaves a window: a second
 * submission passes the same check before the first has committed, and both insert. Only the
 * constraint is evaluated with the row locked, so only the constraint can actually decide.
 *
 * <p>See #61.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=25")
class BookCreateConcurrencyTest {

    @Autowired
    private BookService bookService;
    @Autowired
    private BookMapper bookMapper;

    private static BookRequest sample() {
        return new BookRequest("Clean Architecture", "desc", new BigDecimal("39.99"),
                LocalDate.of(2021, 1, 1), "Wikibooks", 10, null);
    }

    /**
     * Registration submitted twice at once. Two rows would mean two inventories: ten copies
     * entered once would leave the catalogue offering twenty.
     */
    @Test
    void registeringTheSameBookTwiceAtOnce_listsItOnce() throws Exception {
        List<UUID> created = Concurrently.runAtOnce(2, () -> {
            try {
                return bookService.create(sample());
            } catch (DuplicateBookException e) {
                return null;   // the expected way to lose
            }
        });

        assertThat(created.stream().filter(Objects::nonNull).count())
                .as("one submission is accepted, the other is told the book is already listed")
                .isEqualTo(1);
        assertThat(bookMapper.findAll())
                .as("and the catalogue holds one row, carrying one inventory")
                .hasSize(1);
    }
}
