package com.example.bookserver.cart;

import com.example.bookserver.Isbns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.book.Book;
import com.example.bookserver.book.BookMapper;
import com.example.bookserver.book.BookNotFoundException;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
public class CartServiceTest {

    @Autowired
    private CartItemMapper cartItemMapper;
    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private UserMapper userMapper;

    private CartService cartService;

    @BeforeEach
    void setUp() {
        cartService = new CartService(cartItemMapper, bookMapper);
    }

    // --- FK parents must exist before a cart_item can reference them ---
    private UUID persistUser() {
        UUID userUuid = Uuids.newId();
        User user = new User();
        user.setUserUuid(userUuid);
        user.setUserId("u-" + userUuid);
        user.setUserPassword("secret");
        user.setUserName("Jane Doe");
        user.setPhone("010-1234-5678");
        user.setBirthDate(LocalDate.of(1990, 5, 20));
        userMapper.insert(user);
        return userUuid;
    }

    private UUID persistBook() {
        UUID bookUuid = Uuids.newId();
        Book book = new Book();
        book.setBookUuid(bookUuid);
        book.setIsbn(Isbns.next());
        book.setBookTitle("Clean Architecture");
        book.setBookDescription("desc");
        book.setPrice(new BigDecimal("39.99"));
        book.setPublishDate(LocalDate.of(2021, 1, 1));
        book.setPublisher("Wikibooks");
        book.setInventory(10);
        bookMapper.insert(book);
        return bookUuid;
    }

    // addItem inserts a new cart row scoped to the user.
    @Test
    void addItem_insertsNewRow() {
        UUID user = persistUser();
        UUID book = persistBook();

        cartService.addItem(user, book, 2);

        CartItem found = cartItemMapper.findByUserAndBook(user, book);
        assertThat(found).isNotNull();
        assertThat(found.getQuantity()).isEqualTo(2);
    }

    // adding a book already in the cart bumps its quantity, not a duplicate row.
    @Test
    void addItem_accumulatesQuantity_whenAlreadyInCart() {
        UUID user = persistUser();
        UUID book = persistBook();

        cartService.addItem(user, book, 2);
        cartService.addItem(user, book, 3);

        assertThat(cartItemMapper.findByUser(user)).hasSize(1);
        assertThat(cartItemMapper.findByUserAndBook(user, book).getQuantity()).isEqualTo(5);
    }

    // adding a book that does not exist -> 404-style domain error, nothing inserted.
    @Test
    void addItem_throws_whenBookMissing() {
        UUID user = persistUser();
        UUID ghost = Uuids.newId();

        assertThatThrownBy(() -> cartService.addItem(user, ghost, 1))
                .isInstanceOf(BookNotFoundException.class);
        assertThat(cartItemMapper.findByUser(user)).isEmpty();
    }

    // listMyCart returns only the calling user's items, enriched with book title and price.
    @Test
    void listMyCart_isScopedToUser_andEnrichedWithBook() {
        UUID me = persistUser();
        UUID other = persistUser();
        UUID book1 = persistBook();
        UUID book2 = persistBook();
        cartService.addItem(me, book1, 1);
        cartService.addItem(other, book2, 1);

        assertThat(cartService.listMyCart(me))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.getBookUuid()).isEqualTo(book1);
                    assertThat(view.getBookTitle()).isEqualTo("Clean Architecture");
                    assertThat(view.getPrice()).isEqualByComparingTo("39.99");
                    assertThat(view.getQuantity()).isEqualTo(1);
                });
    }

    // changeQuantity overwrites the quantity of an existing item.
    @Test
    void changeQuantity_updatesExisting() {
        UUID user = persistUser();
        UUID book = persistBook();
        cartService.addItem(user, book, 1);

        cartService.changeQuantity(user, book, 7);

        assertThat(cartItemMapper.findByUserAndBook(user, book).getQuantity()).isEqualTo(7);
    }

    // changeQuantity on a book not in the cart -> domain error.
    @Test
    void changeQuantity_throws_whenNotInCart() {
        UUID user = persistUser();
        UUID book = persistBook();

        assertThatThrownBy(() -> cartService.changeQuantity(user, book, 3))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    // removeItem deletes the row; it is idempotent (no error on a missing item).
    @Test
    void removeItem_deletes_andIsIdempotent() {
        UUID user = persistUser();
        UUID book = persistBook();
        cartService.addItem(user, book, 1);

        cartService.removeItem(user, book);
        cartService.removeItem(user, book);   // second call is a no-op

        assertThat(cartItemMapper.findByUserAndBook(user, book)).isNull();
    }
}
