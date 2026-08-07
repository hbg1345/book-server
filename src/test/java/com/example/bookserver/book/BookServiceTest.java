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

    // list returns a page of books, newest first, with the catalogue total alongside.
    @Test
    void list_returnsAPageWithTheTotal() {
        createTitled("First");
        createTitled("Second");
        createTitled("Third");

        BookPage page = bookService.list(0, 2);

        assertThat(page.content()).extracting(Book::getBookTitle).containsExactly("Third", "Second");
        assertThat(page.totalElements()).isEqualTo(3);   // the catalogue, not the page
        assertThat(page.totalPages()).isEqualTo(2);      // 3 books at 2 per page
    }

    // The window moves with the page, and a page past the end is empty rather than an error.
    @Test
    void list_movesTheWindow_andEndsQuietly() {
        createTitled("First");
        createTitled("Second");
        createTitled("Third");

        assertThat(bookService.list(1, 2).content())
                .extracting(Book::getBookTitle).containsExactly("First");
        assertThat(bookService.list(9, 2).content()).isEmpty();
    }

    // The size is a promise about response bytes, so the caller may not name any number it
    // likes — otherwise one request asks for the whole 103k-row catalogue.
    @Test
    void list_rejectsASizeOutsideTheAllowedRange() {
        assertThatThrownBy(() -> bookService.list(0, 0))
                .isInstanceOf(InvalidPageException.class);
        assertThatThrownBy(() -> bookService.list(0, BookService.MAX_SIZE + 1))
                .isInstanceOf(InvalidPageException.class);
    }

    // OFFSET reads and discards everything ahead of the window, so a deep page is the most
    // expensive request the endpoint can take — and one no shopper makes.
    @Test
    void list_refusesToGoDeeperThanTheCap() {
        assertThatThrownBy(() -> bookService.list(BookService.MAX_PAGE + 1, 20))
                .isInstanceOf(InvalidPageException.class);
        assertThatThrownBy(() -> bookService.list(-1, 20))
                .isInstanceOf(InvalidPageException.class);
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

    /**
     * An admin edits a title while the book is selling.
     *
     * <p>BookRequest is shared by create and update and its inventory is @NotNull, so there is
     * no way to change a title without also sending a stock figure — and the figure the client
     * has is the one it read before the admin started typing. BookMapper.update assigns it
     * absolutely (inventory = #{inventory}), so saving the edit puts back stock that has since
     * been sold.
     *
     * <p>This needs no threads to reproduce. The two writes do not overlap: the sale commits
     * long before the edit is saved, and the edit overwrites it anyway. The window is not the
     * microseconds between two statements but the minutes a form sits open.
     *
     * <p>Contrast decrementInventory, which is relative and conditional
     * (SET inventory = inventory - ? WHERE inventory >= ?) and so cannot lose a concurrent
     * write. Two paths write this column and only one of them is disciplined about it.
     */
    @Test
    void update_withStaleInventory_doesNotReviveSoldStock() {
        UUID bookUuid = bookService.create(sampleRequest(null));   // inventory 10

        // the admin's client reads the book to populate its edit form
        int inventoryTheFormRead = bookService.get(bookUuid).getInventory();

        // while the form is open, three copies sell
        assertThat(bookMapper.decrementInventory(bookUuid, 3)).isEqualTo(1);

        // the admin changes only the title and saves, echoing back the stock it was given
        bookService.update(bookUuid, new BookRequest("Clean Architecture, 2nd ed.",
                "A book about software architecture", new BigDecimal("39.99"),
                LocalDate.of(2021, 1, 1), "Wikibooks", inventoryTheFormRead, null));

        assertThat(bookService.get(bookUuid).getInventory())
                .as("the sale stands; a title edit must not restock the book")
                .isEqualTo(7);
    }

    /**
     * What the revived stock is worth: it can be ordered.
     *
     * <p>Inventory is what the catalogue offers and what decrementInventory guards against, so
     * three copies invented by a title edit are three copies a customer can buy and pay for. The
     * same end state as #55, reached from a third direction — there stock was invented by
     * restoring a reservation twice, in #61 by registering a book twice, here by saving a form.
     */
    @Test
    void update_withStaleInventory_doesNotLetTheShopOversell() {
        UUID bookUuid = bookService.create(sampleRequest(null));   // inventory 10
        int inventoryTheFormRead = bookService.get(bookUuid).getInventory();
        bookMapper.decrementInventory(bookUuid, 3);                // 7 left in the shop

        bookService.update(bookUuid, new BookRequest("Clean Architecture, 2nd ed.",
                "A book about software architecture", new BigDecimal("39.99"),
                LocalDate.of(2021, 1, 1), "Wikibooks", inventoryTheFormRead, null));

        assertThat(bookMapper.decrementInventory(bookUuid, 10))
                .as("only seven copies exist, so an order for ten must find insufficient stock")
                .isZero();
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

    // --- helper: a book that exists only to carry a title ---
    private void createTitled(String title) {
        bookService.create(new BookRequest(title, "desc", new BigDecimal("39.99"),
                LocalDate.of(2021, 1, 1), "Wikibooks", 10, null));
    }

    // search returns the matching books.
    @Test
    void search_returnsMatchingBooks() {
        createTitled("Clean Code");
        createTitled("Clean Architecture");
        createTitled("Refactoring");

        assertThat(bookService.search("clean"))
                .extracting(Book::getBookTitle)
                .containsExactlyInAnyOrder("Clean Code", "Clean Architecture");
    }

    // Surrounding whitespace is the client's, not the user's intent: a query pasted with a
    // trailing space should find the same books as one typed without it.
    @Test
    void search_ignoresSurroundingWhitespace() {
        createTitled("Clean Code");

        assertThat(bookService.search("  clean  "))
                .extracting(Book::getBookTitle)
                .containsExactly("Clean Code");
    }

    // A blank query matches every title, which is the whole catalogue wearing a search
    // label. Refuse it rather than let a stray empty input scan 103k rows.
    @Test
    void search_rejectsBlankQuery() {
        createTitled("Clean Code");

        assertThatThrownBy(() -> bookService.search("   "))
                .isInstanceOf(BlankSearchQueryException.class);
        assertThatThrownBy(() -> bookService.search(""))
                .isInstanceOf(BlankSearchQueryException.class);
    }

    // The service, not the caller, decides how many rows a search may return, so no client
    // can ask for the whole catalogue by omitting a limit.
    @Test
    void search_capsResultsAtTheDefaultLimit() {
        for (int i = 0; i < BookService.SEARCH_LIMIT + 5; i++) {
            createTitled("Clean Code volume " + i);
        }

        assertThat(bookService.search("clean")).hasSize(BookService.SEARCH_LIMIT);
    }
}
