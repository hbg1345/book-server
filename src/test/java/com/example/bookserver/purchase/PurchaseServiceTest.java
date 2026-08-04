package com.example.bookserver.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.address.Address;
import com.example.bookserver.address.AddressMapper;
import com.example.bookserver.address.AddressNotFoundException;
import com.example.bookserver.address.InvalidPostalCodeException;
import com.example.bookserver.book.Book;
import com.example.bookserver.book.BookMapper;
import com.example.bookserver.cart.CartItemMapper;
import com.example.bookserver.cart.CartService;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.purchase.dto.PlaceOrderRequest;
import com.example.bookserver.purchase.dto.PlaceOrderRequest.InlineAddress;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
public class PurchaseServiceTest {

    @Autowired
    private PurchaseCurrentMapper currentMapper;
    @Autowired
    private PurchaseHistoryMapper historyMapper;
    @Autowired
    private PurchaseBookHistoryMapper bookHistoryMapper;
    @Autowired
    private OrderAddressMapper orderAddressMapper;
    @Autowired
    private AddressMapper addressMapper;
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
        purchaseService = new PurchaseService(currentMapper, historyMapper, bookHistoryMapper,
                orderAddressMapper, addressMapper, bookMapper, cartService);
    }

    /** A minimal valid order request with a one-off inline delivery address. */
    private static PlaceOrderRequest inlineOrder() {
        return new PlaceOrderRequest(null,
                new InlineAddress("Jane Doe", "010-1234-5678", "KR", "123 Sejong-daero", "5F", "06236"));
    }

    /** Save an address to a user's book and return its id. */
    private UUID persistAddress(UUID userUuid, String recipient, String postalCode) {
        UUID addressUuid = Uuids.newId();
        addressMapper.insert(new Address(addressUuid, userUuid, "Home", recipient, "010-0000-0000",
                "KR", "123 Sejong-daero", "5F", postalCode, false, null));
        return addressUuid;
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

        UUID purchaseUuid = purchaseService.placeOrder(user, inlineOrder());

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

        assertThatThrownBy(() -> purchaseService.placeOrder(user, inlineOrder()))
                .isInstanceOf(EmptyCartException.class);
    }

    // a book short on stock aborts the order; the reserved stock guard leaves inventory intact.
    @Test
    void placeOrder_throws_whenStockInsufficient() {
        UUID user = persistUser();
        UUID book = persistBook("Rare Book", "10.00", 1);
        cartService.addItem(user, book, 2);   // want 2, only 1 in stock

        assertThatThrownBy(() -> purchaseService.placeOrder(user, inlineOrder()))
                .isInstanceOf(InsufficientInventoryException.class);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(1);   // untouched
    }

    // paying a pending order advances it to ORDERED and appends a new state event.
    @Test
    void pay_advancesPendingToOrdered() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID purchaseUuid = purchaseService.placeOrder(user, inlineOrder());

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
        UUID purchaseUuid = purchaseService.placeOrder(user, inlineOrder());
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
        UUID mine = purchaseService.placeOrder(me, inlineOrder());
        cartService.addItem(other, book, 1);
        purchaseService.placeOrder(other, inlineOrder());

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
        UUID purchaseUuid = purchaseService.placeOrder(user, inlineOrder());

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
        UUID purchaseUuid = purchaseService.placeOrder(owner, inlineOrder());

        assertThatThrownBy(() -> purchaseService.getOrder(intruder, purchaseUuid))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // cancelling a pending order restores its reserved stock and records CANCELLED.
    @Test
    void cancel_restoresStock_andRecordsCancelled() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 2);
        UUID purchaseUuid = purchaseService.placeOrder(user, inlineOrder());   // inventory now 8

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
        UUID purchaseUuid = purchaseService.placeOrder(user, inlineOrder());
        purchaseService.cancel(user, purchaseUuid);

        assertThatThrownBy(() -> purchaseService.cancel(user, purchaseUuid))
                .isInstanceOf(IllegalOrderStateException.class);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(10);   // not 12
    }

    // the sweep query returns pending orders older than the cutoff, but not ones that are
    // still fresh (placed after the cutoff) nor ones that have already been paid.
    @Test
    void findUnpaidOrdersBefore_returnsOnlyStalePending() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 30);
        cartService.addItem(user, book, 1);
        UUID pending = purchaseService.placeOrder(user, inlineOrder());         // stays PAYMENT_PENDING
        cartService.addItem(user, book, 1);
        UUID paid = purchaseService.placeOrder(user, inlineOrder());
        purchaseService.pay(user, paid);                        // now ORDERED, not pending

        // a cutoff in the future treats the just-placed order as stale; the paid one is excluded by state
        assertThat(purchaseService.findUnpaidOrdersBefore(LocalDateTime.now().plusMinutes(1)))
                .containsExactly(pending);
        // a cutoff in the past leaves the fresh pending order alone
        assertThat(purchaseService.findUnpaidOrdersBefore(LocalDateTime.now().minusMinutes(30)))
                .isEmpty();
    }

    // expiring an unpaid order cancels it and gives its reserved stock back (reusing cancel).
    @Test
    void expireUnpaidOrder_cancelsPending_andRestoresStock() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 2);
        UUID purchaseUuid = purchaseService.placeOrder(user, inlineOrder());   // inventory now 8

        purchaseService.expireUnpaidOrder(purchaseUuid);

        assertThat(currentMapper.findByPurchaseUuid(purchaseUuid).getPurchaseState())
                .isEqualTo(PurchaseState.CANCELLED);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(10);   // stock released
    }

    // expiry is a no-op on an order that is no longer pending (e.g. paid between scan and act),
    // so its stock is not wrongly restored.
    @Test
    void expireUnpaidOrder_noOp_whenAlreadyPaid() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 2);
        UUID purchaseUuid = purchaseService.placeOrder(user, inlineOrder());   // inventory now 8
        purchaseService.pay(user, purchaseUuid);               // ORDERED

        purchaseService.expireUnpaidOrder(purchaseUuid);

        assertThat(currentMapper.findByPurchaseUuid(purchaseUuid).getPurchaseState())
                .isEqualTo(PurchaseState.ORDERED);             // unchanged
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(8);   // not restored
    }

    // --- delivery address snapshot ---

    // ordering with a saved addressId copies that address's values onto the order.
    @Test
    void placeOrder_withSavedAddress_snapshotsIt() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID addressUuid = persistAddress(user, "Grace Hopper", "06236");

        UUID purchaseUuid = purchaseService.placeOrder(user, new PlaceOrderRequest(addressUuid, null));

        OrderAddress snapshot = orderAddressMapper.findByPurchaseUuid(purchaseUuid);
        assertThat(snapshot.getRecipient()).isEqualTo("Grace Hopper");
        assertThat(snapshot.getPostalCode()).isEqualTo("06236");
        assertThat(snapshot.getCountry()).isEqualTo("KR");
    }

    // an addressId the caller does not own is treated as not-found; no order or reservation happens.
    @Test
    void placeOrder_withOthersAddress_throws_andReservesNothing() {
        UUID owner = persistUser();
        UUID intruder = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(intruder, book, 1);
        UUID othersAddress = persistAddress(owner, "Grace Hopper", "06236");

        assertThatThrownBy(() -> purchaseService.placeOrder(intruder, new PlaceOrderRequest(othersAddress, null)))
                .isInstanceOf(AddressNotFoundException.class);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(10);   // stock untouched (fail-fast, before reserve)
        assertThat(purchaseService.listMyOrders(intruder)).isEmpty();
    }

    // an inline address with a KR postal code of the wrong format is rejected.
    @Test
    void placeOrder_withInvalidKrPostal_throws() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);

        PlaceOrderRequest bad = new PlaceOrderRequest(null,
                new InlineAddress("Jane Doe", "010-1234-5678", "KR", "123 Sejong-daero", "5F", "1234"));   // 4 digits
        assertThatThrownBy(() -> purchaseService.placeOrder(user, bad))
                .isInstanceOf(InvalidPostalCodeException.class);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(10);   // nothing reserved
    }

    // editing the saved address after ordering does NOT change the order's snapshot.
    @Test
    void editingSavedAddress_afterOrder_leavesSnapshotUnchanged() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID addressUuid = persistAddress(user, "Grace Hopper", "06236");
        UUID purchaseUuid = purchaseService.placeOrder(user, new PlaceOrderRequest(addressUuid, null));

        // mutate the address book entry the order was placed from
        addressMapper.update(new Address(addressUuid, user, "Home", "Ada Lovelace", "010-9999-9999",
                "KR", "999 New Road", "1F", "04524", false, null));

        OrderAddress snapshot = orderAddressMapper.findByPurchaseUuid(purchaseUuid);
        assertThat(snapshot.getRecipient()).isEqualTo("Grace Hopper");   // still the original
        assertThat(snapshot.getPostalCode()).isEqualTo("06236");
        assertThat(snapshot.getRoadAddress()).isEqualTo("123 Sejong-daero");
    }
}
