package com.example.bookserver.book;

import com.example.bookserver.Isbns;
import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.book.dto.BookRequest;
import com.example.bookserver.book.dto.UpdateBookRequest;
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
        return sampleRequest(authorUuids, Isbns.next());
    }

    /** The same book under a stated ISBN, for the tests that care which one it is. */
    private BookRequest sampleRequest(List<UUID> authorUuids, String isbn) {
        return new BookRequest(isbn, "Clean Architecture", "A book about software architecture",
                new BigDecimal("39.99"), LocalDate.of(2021, 1, 1), "Wikibooks", 10, authorUuids);
    }

    /** The same book with a new title. No stock figure — the update body no longer carries one. */
    private UpdateBookRequest updateReq(String bookTitle) {
        return new UpdateBookRequest(bookTitle, "A book about software architecture",
                new BigDecimal("39.99"), LocalDate.of(2021, 1, 1), "Wikibooks", null);
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

    /**
     * The same ISBN twice is the same book twice, and the catalogue takes it once.
     *
     * <p>This is what a natural key buys that book_uuid cannot: every create mints a fresh uuid,
     * so two submissions of one form were two rows that nothing could tell apart afterwards —
     * two entries for one title, each holding part of the shop's stock of it.
     *
     * <p>Nothing is asserted about the catalogue afterwards, because nothing can be: Postgres
     * aborts the whole transaction on a failed statement, and this test method is one
     * transaction, so every query after the rejected insert would fail too. That the second book
     * does not survive is checked end to end in {@code BookControllerIntegrationTest}, where each
     * request gets a transaction of its own — which is also how it works in production.
     */
    @Test
    void create_refusesAnIsbnTheCatalogueAlreadyHolds() {
        String isbn = Isbns.next();
        bookService.create(sampleRequest(null, isbn));

        assertThatThrownBy(() -> bookService.create(sampleRequest(null, isbn)))
                .isInstanceOf(DuplicateIsbnException.class);
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

    // update rewrites every catalogue field and replaces the author links — and leaves stock,
    // which is not a catalogue field, exactly where it was.
    @Test
    void update_changesFields_andReplacesAuthors() {
        Author oldAuthor = new Author(Uuids.newId(), "Old Author");
        Author newAuthor = new Author(Uuids.newId(), "New Author");
        authorMapper.insert(oldAuthor);
        authorMapper.insert(newAuthor);

        UUID bookUuid = bookService.create(sampleRequest(List.of(oldAuthor.getAuthorUuid())));

        UpdateBookRequest changed = new UpdateBookRequest("Clean Code", "Updated description",
                new BigDecimal("49.99"), LocalDate.of(2008, 8, 1), "Prentice Hall",
                List.of(newAuthor.getAuthorUuid()));
        bookService.update(bookUuid, changed);

        Book found = bookService.get(bookUuid);
        assertThat(found.getBookTitle()).isEqualTo("Clean Code");
        assertThat(found.getBookDescription()).isEqualTo("Updated description");
        assertThat(found.getPrice()).isEqualByComparingTo("49.99");
        assertThat(found.getPublishDate()).isEqualTo(LocalDate.of(2008, 8, 1));
        assertThat(found.getPublisher()).isEqualTo("Prentice Hall");
        assertThat(found.getInventory()).isEqualTo(10);   // untouched by the edit
        assertThat(found.getAuthors())
                .extracting(Author::getAuthorName)
                .containsExactly("New Author");   // old link replaced, not appended
    }

    /**
     * An admin edits a title while the book is selling.
     *
     * <p>The edit form has no stock figure to echo back, because UpdateBookRequest has no such
     * field. The sale therefore survives an edit saved after it, however long the form sat open
     * — which is the point, since that window is minutes rather than the microseconds between
     * two statements, and no amount of locking would have narrowed it.
     */
    @Test
    void update_doesNotReviveSoldStock() {
        UUID bookUuid = bookService.create(sampleRequest(null));   // inventory 10

        // while the admin's form is open, three copies sell
        assertThat(bookMapper.decrementInventory(bookUuid, 3)).isEqualTo(1);

        // the admin changes only the title and saves
        bookService.update(bookUuid, updateReq("Clean Architecture, 2nd ed."));

        assertThat(bookService.get(bookUuid).getInventory())
                .as("the sale stands; a title edit must not restock the book")
                .isEqualTo(7);
    }

    /**
     * What revived stock would have been worth: it could be ordered.
     *
     * <p>Inventory is what the catalogue offers and what decrementInventory guards against, so
     * copies invented by a title edit would have been copies a customer could buy and pay for —
     * the same end state as #55, reached from a third direction. There stock was invented by
     * restoring a reservation twice, in #61 by registering a book twice, here by saving a form.
     */
    @Test
    void update_doesNotLetTheShopOversell() {
        UUID bookUuid = bookService.create(sampleRequest(null));   // inventory 10
        bookMapper.decrementInventory(bookUuid, 3);                // 7 left in the shop

        bookService.update(bookUuid, updateReq("Clean Architecture, 2nd ed."));

        assertThat(bookMapper.decrementInventory(bookUuid, 10))
                .as("only seven copies exist, so an order for ten must find insufficient stock")
                .isZero();
    }

    // stock moves by a delta, in both directions, and reports what the book holds afterwards.
    @Test
    void adjustStock_appliesDelta_andReturnsNewTotal() {
        UUID bookUuid = bookService.create(sampleRequest(null));   // inventory 10

        assertThat(bookService.adjustStock(bookUuid, 20)).isEqualTo(30);   // received
        assertThat(bookService.adjustStock(bookUuid, -2)).isEqualTo(28);   // written off
        assertThat(bookService.get(bookUuid).getInventory()).isEqualTo(28);
    }

    // a delta composes with a sale whatever order the two arrive in — the property a total
    // cannot have, and the reason this endpoint takes one.
    @Test
    void adjustStock_composesWithASale_inEitherOrder() {
        UUID sellFirst = bookService.create(sampleRequest(null));   // inventory 10
        bookMapper.decrementInventory(sellFirst, 3);
        bookService.adjustStock(sellFirst, 20);

        UUID receiveFirst = bookService.create(sampleRequest(null));   // inventory 10
        bookService.adjustStock(receiveFirst, 20);
        bookMapper.decrementInventory(receiveFirst, 3);

        assertThat(bookService.get(sellFirst).getInventory()).isEqualTo(27);
        assertThat(bookService.get(receiveFirst).getInventory())
                .as("order of arrival must not change the result")
                .isEqualTo(27);
    }

    /**
     * Two adjustments of the same size on the same book are two adjustments. Nothing here
     * recognises a repeat, because nothing in the request says whether twenty more copies
     * arrived twice or the same arrival was submitted twice — the frontend has to not send it.
     */
    @Test
    void adjustStock_repeatedDelta_appliesEachTime() {
        UUID bookUuid = bookService.create(sampleRequest(null));   // inventory 10

        assertThat(bookService.adjustStock(bookUuid, 20)).isEqualTo(30);
        assertThat(bookService.adjustStock(bookUuid, 20)).isEqualTo(50);
    }

    // writing off more copies than the shop holds is refused, and changes nothing.
    @Test
    void adjustStock_belowZero_throwsAndLeavesStockAlone() {
        UUID bookUuid = bookService.create(sampleRequest(null));   // inventory 10

        assertThatThrownBy(() -> bookService.adjustStock(bookUuid, -11))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(bookService.get(bookUuid).getInventory()).isEqualTo(10);
    }

    // emptying the shelf exactly is allowed; zero is a legitimate stock level.
    @Test
    void adjustStock_toExactlyZero_isAllowed() {
        UUID bookUuid = bookService.create(sampleRequest(null));   // inventory 10

        assertThat(bookService.adjustStock(bookUuid, -10)).isZero();
    }

    // moving stock on a book that does not exist is a 404, not a silent no-op.
    @Test
    void adjustStock_throws_whenAbsent() {
        assertThatThrownBy(() -> bookService.adjustStock(Uuids.newId(), 5))
                .isInstanceOf(BookNotFoundException.class);
    }

    // update on a missing id throws.
    @Test
    void update_throws_whenAbsent() {
        assertThatThrownBy(() -> bookService.update(Uuids.newId(), updateReq("Clean Architecture")))
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
        bookService.create(new BookRequest(Isbns.next(), title, "desc", new BigDecimal("39.99"),
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
