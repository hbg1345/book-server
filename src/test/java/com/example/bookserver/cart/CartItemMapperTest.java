package com.example.bookserver.cart;

import com.example.bookserver.Isbns;
import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.book.Book;
import com.example.bookserver.book.BookMapper;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

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
@Sql("/reset.sql")
public class CartItemMapperTest {

    @Autowired
    private CartItemMapper cartItemMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private BookMapper bookMapper;

    // --- helpers: FK parents must exist before a cart_item can reference them ---
    private UUID persistUser() {
        UUID userUuid = Uuids.newId();
        User user = new User();
        user.setUserUuid(userUuid);
        user.setUserId("u-" + userUuid);   // unique login id
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

    // Verifies: a cart item can be inserted and read back by (user, book),
    // its quantity round-trips, and created_at is auto-filled by the DB default.
    @Test
    void insert_and_findByUserAndBook() {
        UUID userUuid = persistUser();
        UUID bookUuid = persistBook();

        cartItemMapper.insert(new CartItem(userUuid, bookUuid, 3, null));

        CartItem found = cartItemMapper.findByUserAndBook(userUuid, bookUuid);
        assertThat(found).isNotNull();
        assertThat(found.getQuantity()).isEqualTo(3);
        assertThat(found.getCreatedAt()).isNotNull();   // filled by the DB default
    }

    // Verifies: findByUser returns every cart item belonging to that user
    // (the whole cart), across multiple different books.
    @Test
    void findByUser_returnsAllItems() {
        UUID userUuid = persistUser();
        UUID book1 = persistBook();
        UUID book2 = persistBook();

        cartItemMapper.insert(new CartItem(userUuid, book1, 1, null));
        cartItemMapper.insert(new CartItem(userUuid, book2, 2, null));

        assertThat(cartItemMapper.findByUser(userUuid))
                .extracting(CartItem::getBookUuid)
                .containsExactlyInAnyOrder(book1, book2);
    }

    // Verifies: findByUserWithBook joins each cart row with its book, exposing
    // the title and price alongside the quantity (the enriched cart view).
    @Test
    void findByUserWithBook_joinsBookTitleAndPrice() {
        UUID userUuid = persistUser();
        UUID bookUuid = persistBook();
        cartItemMapper.insert(new CartItem(userUuid, bookUuid, 3, null));

        assertThat(cartItemMapper.findByUserWithBook(userUuid))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.getBookUuid()).isEqualTo(bookUuid);
                    assertThat(view.getBookTitle()).isEqualTo("Clean Architecture");
                    assertThat(view.getPrice()).isEqualByComparingTo(new BigDecimal("39.99"));
                    assertThat(view.getQuantity()).isEqualTo(3);
                    assertThat(view.getCreatedAt()).isNotNull();
                });
    }

    // Verifies: updateQuantity changes the quantity of an existing cart item
    // (identified by the composite user+book key) and the new value persists.
    @Test
    void updateQuantity() {
        UUID userUuid = persistUser();
        UUID bookUuid = persistBook();
        cartItemMapper.insert(new CartItem(userUuid, bookUuid, 1, null));

        cartItemMapper.updateQuantity(userUuid, bookUuid, 5);

        assertThat(cartItemMapper.findByUserAndBook(userUuid, bookUuid).getQuantity())
                .isEqualTo(5);
    }

    // Verifies: deleteByUser empties the whole cart (all books) for that user,
    // e.g. after its items are turned into an order.
    @Test
    void deleteByUser_emptiesWholeCart() {
        UUID userUuid = persistUser();
        cartItemMapper.insert(new CartItem(userUuid, persistBook(), 1, null));
        cartItemMapper.insert(new CartItem(userUuid, persistBook(), 2, null));

        cartItemMapper.deleteByUser(userUuid);

        assertThat(cartItemMapper.findByUser(userUuid)).isEmpty();
    }

    // Verifies: delete removes the cart item so it can no longer be found
    // (i.e. the book was taken out of the user's cart).
    @Test
    void delete() {
        UUID userUuid = persistUser();
        UUID bookUuid = persistBook();
        cartItemMapper.insert(new CartItem(userUuid, bookUuid, 1, null));

        cartItemMapper.delete(userUuid, bookUuid);

        assertThat(cartItemMapper.findByUserAndBook(userUuid, bookUuid)).isNull();
    }
}
