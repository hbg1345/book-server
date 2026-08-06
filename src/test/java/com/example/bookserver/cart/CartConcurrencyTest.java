package com.example.bookserver.cart;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import com.example.bookserver.Concurrently;
import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.book.Book;
import com.example.bookserver.book.BookMapper;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adding to the cart is read-then-write, and {@code cart_item} is keyed on (user, book) — so
 * two clicks on "add to cart" have a way to go wrong in each direction: a duplicate insert that
 * the primary key rejects, or two additions that overwrite one another.
 *
 * <p>See #58.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=25")
class CartConcurrencyTest {

    @Autowired
    private CartService cartService;
    @Autowired
    private CartItemMapper cartItemMapper;
    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private UserMapper userMapper;

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
        book.setBookTitle("Clean Code");
        book.setBookDescription("desc");
        book.setPrice(new BigDecimal("10.00"));
        book.setPublishDate(LocalDate.of(2021, 1, 1));
        book.setPublisher("Wikibooks");
        book.setInventory(100);
        bookMapper.insert(book);
        return bookUuid;
    }

    /**
     * The book is not in the cart yet, so both calls read {@code null} and both insert. The
     * loser hits the primary key, and nothing maps that to an HTTP status — the user gets a 500
     * for pressing a button twice.
     */
    @Test
    void addingTheSameBookTwiceAtOnce_doesNotFail() throws Exception {
        UUID userUuid = persistUser();
        UUID bookUuid = persistBook();

        Concurrently.runAtOnce(2, () -> {
            cartService.addItem(userUuid, bookUuid, 1);
            return null;
        });

        assertThat(cartItemMapper.findByUserAndBook(userUuid, bookUuid).getQuantity())
                .as("both additions land: 1 + 1")
                .isEqualTo(2);
    }

    /**
     * The book is already in the cart, so both calls read the same quantity and both write
     * {@code read + 1}. One addition is overwritten and disappears without any error — the cart
     * is simply wrong, which is worse than a visible failure.
     */
    @Test
    void addingToAnExistingLine_losesNoQuantity() throws Exception {
        UUID userUuid = persistUser();
        UUID bookUuid = persistBook();
        cartService.addItem(userUuid, bookUuid, 1);

        Concurrently.runAtOnce(2, () -> {
            cartService.addItem(userUuid, bookUuid, 1);
            return null;
        });

        assertThat(cartItemMapper.findByUserAndBook(userUuid, bookUuid).getQuantity())
                .as("1 already there, plus two additions of 1")
                .isEqualTo(3);
    }
}
