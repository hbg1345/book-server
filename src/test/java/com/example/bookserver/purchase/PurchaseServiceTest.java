package com.example.bookserver.purchase;

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
import com.example.bookserver.cart.CartItemMapper;
import com.example.bookserver.cart.CartService;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/schema.sql")
public class PurchaseServiceTest {

    @Autowired
    private PurchaseCurrentMapper currentMapper;
    @Autowired
    private PurchaseHistoryMapper historyMapper;
    @Autowired
    private PurchaseBookHistoryMapper bookHistoryMapper;
    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private CartItemMapper cartItemMapper;
    @Autowired
    private UserMapper userMapper;

    private CartService cartService;
    private PurchaseService purchaseService;

    @BeforeEach
    void setUp() {
        cartService = new CartService(cartItemMapper, bookMapper);
        purchaseService = new PurchaseService(currentMapper, historyMapper, bookHistoryMapper, bookMapper, cartService);
    }

    // --- FK parents ---
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

    private UUID persistBook(String title, String price, int inventory) {
        UUID bookUuid = Uuids.newId();
        Book book = new Book();
        book.setBookUuid(bookUuid);
        book.setBookTitle(title);
        book.setBookDescription("desc");
        book.setPrice(new BigDecimal(price));
        book.setPublishDate(LocalDate.of(2021, 1, 1));
        book.setPublisher("Wikibooks");
        book.setInventory(inventory);
        bookMapper.insert(book);
        return bookUuid;
    }

    // placeOrder turns the cart into a PAYMENT_PENDING order, reserving stock and clearing the cart.
    @Test
    void placeOrder_recordsPending_reservesStock_emptiesCart() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 2);

        UUID purchaseUuid = purchaseService.placeOrder(user);

        PurchaseCurrent current = currentMapper.findByPurchaseUuid(purchaseUuid);
        assertThat(current.getPurchaseState()).isEqualTo(PurchaseState.PAYMENT_PENDING);
        assertThat(current.getUserUuid()).isEqualTo(user);
        assertThat(current.getPrice()).isEqualByComparingTo("79.98");          // 39.99 * 2
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(8);       // reserved
        assertThat(cartService.listMyCart(user)).isEmpty();                      // cart drained
        assertThat(bookHistoryMapper.findByHistoryUuid(current.getHistoryUuid()))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.getBookUuid()).isEqualTo(book);
                    assertThat(b.getQuantity()).isEqualTo(2);
                });
    }

    // an empty cart cannot become an order.
    @Test
    void placeOrder_throws_whenCartEmpty() {
        UUID user = persistUser();

        assertThatThrownBy(() -> purchaseService.placeOrder(user))
                .isInstanceOf(EmptyCartException.class);
    }

    // a book short on stock aborts the order; the reserved stock guard leaves inventory intact.
    @Test
    void placeOrder_throws_whenStockInsufficient() {
        UUID user = persistUser();
        UUID book = persistBook("Rare Book", "10.00", 1);
        cartService.addItem(user, book, 2);   // want 2, only 1 in stock

        assertThatThrownBy(() -> purchaseService.placeOrder(user))
                .isInstanceOf(InsufficientInventoryException.class);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(1);   // untouched
    }

    // paying a pending order advances it to ORDERED and appends a new state event.
    @Test
    void pay_advancesPendingToOrdered() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID purchaseUuid = purchaseService.placeOrder(user);

        purchaseService.pay(user, purchaseUuid);

        assertThat(currentMapper.findByPurchaseUuid(purchaseUuid).getPurchaseState())
                .isEqualTo(PurchaseState.ORDERED);
        assertThat(historyMapper.findAllByPurchaseUuid(purchaseUuid)).hasSize(2);   // PENDING + ORDERED
    }

    // paying an order that is not pending is rejected.
    @Test
    void pay_throws_whenNotPending() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID purchaseUuid = purchaseService.placeOrder(user);
        purchaseService.pay(user, purchaseUuid);   // now ORDERED

        assertThatThrownBy(() -> purchaseService.pay(user, purchaseUuid))
                .isInstanceOf(IllegalOrderStateException.class);
    }

    // order listing is scoped to the calling user.
    @Test
    void listMyOrders_isScopedToUser() {
        UUID me = persistUser();
        UUID other = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(me, book, 1);
        UUID mine = purchaseService.placeOrder(me);
        cartService.addItem(other, book, 1);
        purchaseService.placeOrder(other);

        assertThat(purchaseService.listMyOrders(me))
                .extracting(PurchaseCurrent::getPurchaseUuid)
                .containsExactly(mine);
    }

    // order detail carries the header, the books (with titles) and the state timeline.
    @Test
    void getOrder_returnsHeaderItemsAndHistory() {
        UUID user = persistUser();
        UUID book1 = persistBook("Domain-Driven Design", "50.00", 10);
        UUID book2 = persistBook("A Philosophy of Software Design", "20.00", 10);
        cartService.addItem(user, book1, 1);
        cartService.addItem(user, book2, 2);
        UUID purchaseUuid = purchaseService.placeOrder(user);

        OrderDetail detail = purchaseService.getOrder(user, purchaseUuid);

        assertThat(detail.current().getPurchaseState()).isEqualTo(PurchaseState.PAYMENT_PENDING);
        assertThat(detail.items())
                .extracting(OrderBookItem::getBookTitle)   // ordered by title in the join
                .containsExactly("A Philosophy of Software Design", "Domain-Driven Design");
        assertThat(detail.history()).hasSize(1);
    }

    // one user cannot read another user's order.
    @Test
    void getOrder_throws_whenNotOwner() {
        UUID owner = persistUser();
        UUID intruder = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(owner, book, 1);
        UUID purchaseUuid = purchaseService.placeOrder(owner);

        assertThatThrownBy(() -> purchaseService.getOrder(intruder, purchaseUuid))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // cancelling a pending order restores its reserved stock and records CANCELLED.
    @Test
    void cancel_restoresStock_andRecordsCancelled() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 2);
        UUID purchaseUuid = purchaseService.placeOrder(user);   // inventory now 8

        purchaseService.cancel(user, purchaseUuid);

        assertThat(currentMapper.findByPurchaseUuid(purchaseUuid).getPurchaseState())
                .isEqualTo(PurchaseState.CANCELLED);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(10);   // stock given back
    }

    // an already-cancelled order cannot be cancelled again (and stock is not double-refunded).
    @Test
    void cancel_throws_whenAlreadyCancelled() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 2);
        UUID purchaseUuid = purchaseService.placeOrder(user);
        purchaseService.cancel(user, purchaseUuid);

        assertThatThrownBy(() -> purchaseService.cancel(user, purchaseUuid))
                .isInstanceOf(IllegalOrderStateException.class);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(10);   // not 12
    }
}
