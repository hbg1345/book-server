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
import com.example.bookserver.payment.FakePaymentGateway;
import com.example.bookserver.payment.OpenedPayment;
import com.example.bookserver.payment.Payment;
import com.example.bookserver.payment.PaymentAmountMismatchException;
import com.example.bookserver.payment.PaymentIntentFailedException;
import com.example.bookserver.payment.PaymentMapper;
import com.example.bookserver.payment.PaymentStatus;
import com.example.bookserver.payment.RefundFailedException;
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
    private PaymentMapper paymentMapper;
    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private CartItemMapper cartItemMapper;
    @Autowired
    private UserMapper userMapper;

    private CartService cartService;
    private FakePaymentGateway gateway;
    private PurchaseService purchaseService;

    @BeforeEach
    void setUp() {
        cartService = new CartService(cartItemMapper, bookMapper);
        gateway = new FakePaymentGateway();   // succeeds by default
        purchaseService = new PurchaseService(currentMapper, historyMapper, bookHistoryMapper,
                orderAddressMapper, addressMapper, paymentMapper, gateway, bookMapper, cartService);
    }

    /**
     * Drive an order all the way through payment: open the intent, then settle it the way the
     * provider's webhook would. Leaves the order ORDERED with a PAID payment.
     */
    private void payFor(UUID userUuid, UUID purchaseUuid, String amount) {
        OpenedPayment opened = purchaseService.openPaymentIntent(userUuid, purchaseUuid);
        purchaseService.markPaymentSucceeded(opened.payment().getProviderTxnId(), new BigDecimal(amount));
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

    // --- payment intent (#25) ---

    // opening an intent persists a PENDING payment for the SERVER's order total and returns the
    // client secret — but does NOT advance the order: only the provider's webhook can do that.
    @Test
    void openPaymentIntent_persistsPendingPayment_andLeavesOrderPending() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());

        OpenedPayment opened = purchaseService.openPaymentIntent(user, p);

        assertThat(opened.clientSecret()).isNotBlank();
        assertThat(opened.payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
        Payment saved = paymentMapper.findByPurchaseUuid(p);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getProvider()).isEqualTo("FAKE");
        assertThat(saved.getProviderTxnId()).isNotBlank();
        assertThat(saved.getAmount()).isEqualByComparingTo("39.99");
        assertThat(gateway.lastIntentAmount()).isEqualByComparingTo("39.99");   // server total
        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState())
                .isEqualTo(PurchaseState.PAYMENT_PENDING);
        assertThat(historyMapper.findAllByPurchaseUuid(p)).hasSize(1);   // no new state event
    }

    // re-opening the intent for the same order replays the one intent and inserts no second row.
    @Test
    void openPaymentIntent_retry_reusesTheSameIntent() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());

        OpenedPayment first = purchaseService.openPaymentIntent(user, p);
        OpenedPayment retry = purchaseService.openPaymentIntent(user, p);

        assertThat(retry.payment().getPaymentUuid()).isEqualTo(first.payment().getPaymentUuid());
        assertThat(retry.clientSecret()).isEqualTo(first.clientSecret());   // provider replayed it
    }

    // an order that is no longer awaiting payment cannot open an intent.
    @Test
    void openPaymentIntent_throws_whenNotPending() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());
        payFor(user, p, "39.99");   // now ORDERED

        assertThatThrownBy(() -> purchaseService.openPaymentIntent(user, p))
                .isInstanceOf(IllegalOrderStateException.class);
    }

    // a provider that will not open an intent surfaces as an error and persists nothing.
    @Test
    void openPaymentIntent_providerFailure_persistsNothing() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());
        gateway.setOpenSucceed(false);

        assertThatThrownBy(() -> purchaseService.openPaymentIntent(user, p))
                .isInstanceOf(PaymentIntentFailedException.class);
        assertThat(paymentMapper.findByPurchaseUuid(p)).isNull();
        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState())
                .isEqualTo(PurchaseState.PAYMENT_PENDING);
    }

    // --- webhook settlement (#25) ---

    // a confirmed charge marks the payment PAID and advances the order to ORDERED.
    @Test
    void markPaymentSucceeded_marksPaid_andAdvancesOrder() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());
        OpenedPayment opened = purchaseService.openPaymentIntent(user, p);

        purchaseService.markPaymentSucceeded(opened.payment().getProviderTxnId(), new BigDecimal("39.99"));

        assertThat(paymentMapper.findByPurchaseUuid(p).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState()).isEqualTo(PurchaseState.ORDERED);
        assertThat(historyMapper.findAllByPurchaseUuid(p)).hasSize(2);   // PENDING + ORDERED
    }

    // providers re-deliver webhooks: a second delivery must not append a second ORDERED event.
    @Test
    void markPaymentSucceeded_isIdempotent() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());
        OpenedPayment opened = purchaseService.openPaymentIntent(user, p);
        String intentId = opened.payment().getProviderTxnId();

        purchaseService.markPaymentSucceeded(intentId, new BigDecimal("39.99"));
        purchaseService.markPaymentSucceeded(intentId, new BigDecimal("39.99"));   // re-delivered

        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState()).isEqualTo(PurchaseState.ORDERED);
        assertThat(historyMapper.findAllByPurchaseUuid(p)).hasSize(2);   // still just PENDING + ORDERED
    }

    // a charge for the wrong amount is refused — the last place tampering can be caught.
    @Test
    void markPaymentSucceeded_amountMismatch_isRejected() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());
        OpenedPayment opened = purchaseService.openPaymentIntent(user, p);

        assertThatThrownBy(() -> purchaseService.markPaymentSucceeded(
                opened.payment().getProviderTxnId(), new BigDecimal("9.99")))
                .isInstanceOf(PaymentAmountMismatchException.class);
        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState())
                .isEqualTo(PurchaseState.PAYMENT_PENDING);
    }

    // a webhook for an intent we never opened is ignored, not an error (it would be retried forever).
    @Test
    void markPaymentSucceeded_unknownIntent_isIgnored() {
        purchaseService.markPaymentSucceeded("pi_never_seen", new BigDecimal("39.99"));
    }

    // a failed charge is recorded for audit and leaves the order payable.
    @Test
    void markPaymentFailed_recordsFailure_andLeavesOrderPending() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());
        OpenedPayment opened = purchaseService.openPaymentIntent(user, p);

        purchaseService.markPaymentFailed(opened.payment().getProviderTxnId());

        assertThat(paymentMapper.findByPurchaseUuid(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState())
                .isEqualTo(PurchaseState.PAYMENT_PENDING);
    }

    // a late "failed" delivery must never undo a payment that already succeeded.
    @Test
    void markPaymentFailed_doesNotDowngradeAPaidPayment() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());
        OpenedPayment opened = purchaseService.openPaymentIntent(user, p);
        String intentId = opened.payment().getProviderTxnId();
        purchaseService.markPaymentSucceeded(intentId, new BigDecimal("39.99"));

        purchaseService.markPaymentFailed(intentId);   // arrives out of order

        assertThat(paymentMapper.findByPurchaseUuid(p).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState()).isEqualTo(PurchaseState.ORDERED);
    }

    /**
     * A refund reversed after the fact marks the payment and nothing else. The buyer has already
     * been told the order is refunded and the stock is already back, so undoing either would
     * compound the problem — the payment row is the only honest record that money is still owed.
     */
    @Test
    void markRefundFailed_marksThePayment_andLeavesTheOrderRefunded() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());
        OpenedPayment opened = purchaseService.openPaymentIntent(user, p);
        String intentId = opened.payment().getProviderTxnId();
        purchaseService.markPaymentSucceeded(intentId, new BigDecimal("39.99"));
        purchaseService.cancel(user, p);   // paid -> refunded
        int stockAfterRefund = bookMapper.findById(book).getInventory();

        purchaseService.markRefundFailed(intentId, "expired_or_canceled_card");

        assertThat(paymentMapper.findByPurchaseUuid(p).getStatus())
                .isEqualTo(PaymentStatus.REFUND_FAILED);
        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState())
                .isEqualTo(PurchaseState.REFUNDED);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(stockAfterRefund);
    }

    // Stripe re-delivers webhooks; a second copy must not produce a second anything.
    @Test
    void markRefundFailed_isIdempotent() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());
        OpenedPayment opened = purchaseService.openPaymentIntent(user, p);
        String intentId = opened.payment().getProviderTxnId();
        purchaseService.markPaymentSucceeded(intentId, new BigDecimal("39.99"));
        purchaseService.cancel(user, p);

        purchaseService.markRefundFailed(intentId, "expired_or_canceled_card");
        purchaseService.markRefundFailed(intentId, "expired_or_canceled_card");

        assertThat(paymentMapper.findByPurchaseUuid(p).getStatus())
                .isEqualTo(PaymentStatus.REFUND_FAILED);
    }

    // an intent we never opened must not wedge Stripe's retry loop with an exception.
    @Test
    void markRefundFailed_ignoresAnUnknownIntent() {
        purchaseService.markRefundFailed("pi_never_seen", "expired_or_canceled_card");
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
        payFor(user, paid, "39.99");   // now ORDERED, not pending

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
        payFor(user, purchaseUuid, "79.98");   // ORDERED

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
        assertThat(snapshot.getSourceAddressUuid()).isEqualTo(addressUuid);   // breadcrumb to the saved address
    }

    // a one-off inline order records no source address (breadcrumb is null).
    @Test
    void placeOrder_withInlineAddress_hasNullSource() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);

        UUID purchaseUuid = purchaseService.placeOrder(user, inlineOrder());

        assertThat(orderAddressMapper.findByPurchaseUuid(purchaseUuid).getSourceAddressUuid()).isNull();
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

    // --- fulfillment lifecycle (#26) ---

    /** Place an order and pay it, leaving it at ORDERED (the start of the fulfillment lifecycle). */
    private UUID placedAndPaid(UUID user, UUID book) {
        cartService.addItem(user, book, 1);
        UUID purchaseUuid = purchaseService.placeOrder(user, inlineOrder());
        BigDecimal total = currentMapper.findByPurchaseUuid(purchaseUuid).getPrice();
        payFor(user, purchaseUuid, total.toPlainString());   // -> ORDERED
        return purchaseUuid;
    }

    // admin prepare: ORDERED -> PREPARING.
    @Test
    void prepare_advancesOrderedToPreparing() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = placedAndPaid(user, book);

        purchaseService.prepare(p);

        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState()).isEqualTo(PurchaseState.PREPARING);
    }

    // an illegal jump (preparing an order still at PAYMENT_PENDING) is rejected.
    @Test
    void prepare_rejectsIllegalTransition() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());   // PAYMENT_PENDING, not ORDERED

        assertThatThrownBy(() -> purchaseService.prepare(p))
                .isInstanceOf(IllegalOrderStateException.class);
    }

    // admin ship: PREPARING -> SHIPPING, capturing the tracking number.
    @Test
    void ship_setsTrackingNumber_andAdvances() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = placedAndPaid(user, book);
        purchaseService.prepare(p);

        purchaseService.ship(p, "1Z999AA10123456784");

        PurchaseCurrent c = currentMapper.findByPurchaseUuid(p);
        assertThat(c.getPurchaseState()).isEqualTo(PurchaseState.SHIPPING);
        assertThat(c.getTrackingNumber()).isEqualTo("1Z999AA10123456784");
    }

    // full happy path ORDERED -> ... -> CONFIRMED; tracking number survives later transitions.
    @Test
    void deliver_thenConfirm_completesLifecycle_trackingPreserved() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = placedAndPaid(user, book);
        purchaseService.prepare(p);
        purchaseService.ship(p, "TRACK-1");
        purchaseService.deliver(p);
        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState()).isEqualTo(PurchaseState.DELIVERED);

        purchaseService.confirm(user, p);

        PurchaseCurrent c = currentMapper.findByPurchaseUuid(p);
        assertThat(c.getPurchaseState()).isEqualTo(PurchaseState.CONFIRMED);
        assertThat(c.getTrackingNumber()).isEqualTo("TRACK-1");   // not wiped by deliver/confirm
    }

    // confirm is the buyer's action and is owner-scoped: another user cannot confirm it.
    @Test
    void confirm_isOwnerScoped() {
        UUID owner = persistUser();
        UUID intruder = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = placedAndPaid(owner, book);
        purchaseService.prepare(p);
        purchaseService.ship(p, "T");
        purchaseService.deliver(p);

        assertThatThrownBy(() -> purchaseService.confirm(intruder, p))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // confirming before delivery is rejected.
    @Test
    void confirm_rejectsWhenNotDelivered() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = placedAndPaid(user, book);   // ORDERED, not DELIVERED

        assertThatThrownBy(() -> purchaseService.confirm(user, p))
                .isInstanceOf(IllegalOrderStateException.class);
    }

    // admin fulfillment transitions are NOT owner-scoped: they act on any user's order.
    @Test
    void adminTransition_worksOnAnothersOrder() {
        UUID buyer = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = placedAndPaid(buyer, book);

        purchaseService.prepare(p);   // no user arg -> not scoped to the buyer

        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState()).isEqualTo(PurchaseState.PREPARING);
    }

    // acting on a non-existent order -> 404.
    @Test
    void prepare_throws_whenOrderMissing() {
        assertThatThrownBy(() -> purchaseService.prepare(Uuids.newId()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // --- refund / return (#25 PR-2) ---

    /** Drive an order to DELIVERED (paid, prepared, shipped, delivered). */
    private UUID delivered(UUID user, UUID book) {
        UUID p = placedAndPaid(user, book);
        purchaseService.prepare(p);
        purchaseService.ship(p, "TRACK");
        purchaseService.deliver(p);
        return p;
    }

    // cancelling a PAID (ORDERED) order refunds it: REFUND_REQUESTED -> REFUNDED, payment REFUNDED,
    // stock restored.
    @Test
    void cancel_afterPayment_refunds_andRestoresStock() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = placedAndPaid(user, book);   // ORDERED, inventory 9

        purchaseService.cancel(user, p);

        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState()).isEqualTo(PurchaseState.REFUNDED);
        assertThat(paymentMapper.findByPurchaseUuid(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(10);   // stock given back
        assertThat(gateway.refundCount()).isEqualTo(1);
        assertThat(gateway.lastRefundedAmount()).isEqualByComparingTo("39.99");
        // the timeline passed through REFUND_REQUESTED then REFUNDED
        assertThat(historyMapper.findAllByPurchaseUuid(p))
                .extracting(PurchaseHistory::getPurchaseState)
                .containsSubsequence(PurchaseState.REFUND_REQUESTED, PurchaseState.REFUNDED);
    }

    // cancelling an UNPAID (PAYMENT_PENDING) order just releases stock -> CANCELLED, no refund.
    @Test
    void cancel_beforePayment_cancels_withoutRefund() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        cartService.addItem(user, book, 1);
        UUID p = purchaseService.placeOrder(user, inlineOrder());   // PAYMENT_PENDING

        purchaseService.cancel(user, p);

        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState()).isEqualTo(PurchaseState.CANCELLED);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(10);
        assertThat(gateway.refundCount()).isZero();
    }

    // a failed refund leaves the paid order unchanged (rolls back), so it can be retried.
    @Test
    void cancel_afterPayment_refundFailure_leavesOrderPaid() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = placedAndPaid(user, book);
        gateway.setRefundSucceed(false);

        assertThatThrownBy(() -> purchaseService.cancel(user, p))
                .isInstanceOf(RefundFailedException.class);

        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState()).isEqualTo(PurchaseState.ORDERED);
        assertThat(paymentMapper.findByPurchaseUuid(p).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(9);   // not restored
    }

    // returning a DELIVERED order refunds it and restores stock.
    @Test
    void returnOrder_afterDelivered_refunds_andRestoresStock() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = delivered(user, book);   // inventory 9

        purchaseService.returnOrder(user, p);

        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState()).isEqualTo(PurchaseState.REFUNDED);
        assertThat(paymentMapper.findByPurchaseUuid(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(10);
        assertThat(gateway.refundCount()).isEqualTo(1);
    }

    // a CONFIRMED order can also be returned (refunded).
    @Test
    void returnOrder_afterConfirmed_refunds() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = delivered(user, book);
        purchaseService.confirm(user, p);   // DELIVERED -> CONFIRMED

        purchaseService.returnOrder(user, p);

        assertThat(currentMapper.findByPurchaseUuid(p).getPurchaseState()).isEqualTo(PurchaseState.REFUNDED);
    }

    // an order that has not been delivered cannot be returned.
    @Test
    void returnOrder_beforeDelivered_isRejected() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = placedAndPaid(user, book);   // ORDERED, not delivered

        assertThatThrownBy(() -> purchaseService.returnOrder(user, p))
                .isInstanceOf(IllegalOrderStateException.class);
        assertThat(gateway.refundCount()).isZero();
    }

    // return is the buyer's action and is owner-scoped.
    @Test
    void returnOrder_isOwnerScoped() {
        UUID owner = persistUser();
        UUID intruder = persistUser();
        UUID book = persistBook("Clean Architecture", "39.99", 10);
        UUID p = delivered(owner, book);

        assertThatThrownBy(() -> purchaseService.returnOrder(intruder, p))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // --- partial cancellation ---

    // One copy of a two-copy line goes back: that stock is returned, the order keeps the rest,
    // and the total falls by exactly one copy's price.
    @Test
    void cancelItem_returnsOneCopy_andKeepsTheRest() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Code", "10.00", 10);
        cartService.addItem(user, book, 2);
        UUID purchase = purchaseService.placeOrder(user, inlineOrder());
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(8);

        purchaseService.cancelItem(user, purchase, book, 1);

        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(9);
        PurchaseCurrent current = currentMapper.findByPurchaseUuid(purchase);
        assertThat(current.getPurchaseState()).isEqualTo(PurchaseState.PARTIALLY_CANCELLED);
        assertThat(current.getPrice()).isEqualByComparingTo("10.00");
        assertThat(bookHistoryMapper.findByHistoryUuid(current.getHistoryUuid()))
                .singleElement()
                .satisfies(line -> assertThat(line.getQuantity()).isEqualTo(1));
    }

    // Cancelling one book of two leaves the other line untouched.
    @Test
    void cancelItem_leavesTheOtherLinesAlone() {
        UUID user = persistUser();
        UUID kept = persistBook("Refactoring", "15.00", 10);
        UUID dropped = persistBook("Clean Code", "10.00", 10);
        cartService.addItem(user, kept, 1);
        cartService.addItem(user, dropped, 1);
        UUID purchase = purchaseService.placeOrder(user, inlineOrder());

        purchaseService.cancelItem(user, purchase, dropped, 1);

        PurchaseCurrent current = currentMapper.findByPurchaseUuid(purchase);
        assertThat(current.getPrice()).isEqualByComparingTo("15.00");
        assertThat(bookHistoryMapper.findByHistoryUuid(current.getHistoryUuid()))
                .extracting(PurchaseBookHistory::getBookUuid)
                .containsExactly(kept);
        assertThat(bookMapper.findById(kept).getInventory()).isEqualTo(9);     // still reserved
        assertThat(bookMapper.findById(dropped).getInventory()).isEqualTo(10); // given back
    }

    // Cancelling the last copy is the whole order, so it lands in the terminal state a full
    // cancel would have reached rather than leaving an empty half-cancelled order.
    @Test
    void cancelItem_endsTheOrder_whenNothingIsLeft() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Code", "10.00", 10);
        cartService.addItem(user, book, 2);
        UUID purchase = purchaseService.placeOrder(user, inlineOrder());

        purchaseService.cancelItem(user, purchase, book, 2);

        assertThat(currentMapper.findByPurchaseUuid(purchase).getPurchaseState())
                .isEqualTo(PurchaseState.CANCELLED);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(10);
    }

    // A paid order refunds that share of the money, and the payment records how much went back
    // while staying PAID — the rest of the charge is still held.
    @Test
    void cancelItem_refundsThatShare_ofAPaidOrder() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Code", "10.00", 10);
        cartService.addItem(user, book, 2);
        UUID purchase = purchaseService.placeOrder(user, inlineOrder());
        payFor(user, purchase, "20.00");

        purchaseService.cancelItem(user, purchase, book, 1);

        assertThat(gateway.lastRefundedAmount()).isEqualByComparingTo("10.00");
        Payment payment = paymentMapper.findByPurchaseUuid(purchase);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("10.00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);   // half is still held
        assertThat(currentMapper.findByPurchaseUuid(purchase).getPurchaseState())
                .isEqualTo(PurchaseState.PARTIALLY_REFUNDED);
    }

    // Two partial refunds must reach the provider as two refunds. A key that named only the order
    // would make the second look like a retry of the first: the provider answers success and
    // moves no money.
    @Test
    void cancelItem_refundsAgain_afterAnEarlierPartialRefund() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Code", "10.00", 10);
        cartService.addItem(user, book, 3);
        UUID purchase = purchaseService.placeOrder(user, inlineOrder());
        payFor(user, purchase, "30.00");

        purchaseService.cancelItem(user, purchase, book, 1);
        purchaseService.cancelItem(user, purchase, book, 1);

        assertThat(gateway.refundCount()).isEqualTo(2);
        Payment payment = paymentMapper.findByPurchaseUuid(purchase);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("20.00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    // Cancelling the remainder of a partly-refunded order returns the rest and closes it out.
    @Test
    void cancel_refundsTheRemainder_ofAPartlyRefundedOrder() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Code", "10.00", 10);
        cartService.addItem(user, book, 2);
        UUID purchase = purchaseService.placeOrder(user, inlineOrder());
        payFor(user, purchase, "20.00");
        purchaseService.cancelItem(user, purchase, book, 1);

        purchaseService.cancel(user, purchase);

        Payment payment = paymentMapper.findByPurchaseUuid(purchase);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("20.00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(currentMapper.findByPurchaseUuid(purchase).getPurchaseState())
                .isEqualTo(PurchaseState.REFUNDED);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(10);
    }

    // More copies than the order holds, or none at all, is a bad request rather than a silent
    // partial success.
    @Test
    void cancelItem_rejectsAQuantityTheLineCannotGiveUp() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Code", "10.00", 10);
        cartService.addItem(user, book, 2);
        UUID purchase = purchaseService.placeOrder(user, inlineOrder());

        assertThatThrownBy(() -> purchaseService.cancelItem(user, purchase, book, 3))
                .isInstanceOf(InvalidCancellationException.class);
        assertThatThrownBy(() -> purchaseService.cancelItem(user, purchase, book, 0))
                .isInstanceOf(InvalidCancellationException.class);
        assertThat(bookMapper.findById(book).getInventory()).isEqualTo(8);   // untouched
    }

    // A book the order never held.
    @Test
    void cancelItem_throws_whenTheOrderHasNoSuchLine() {
        UUID user = persistUser();
        UUID ordered = persistBook("Clean Code", "10.00", 10);
        UUID other = persistBook("Refactoring", "15.00", 10);
        cartService.addItem(user, ordered, 1);
        UUID purchase = purchaseService.placeOrder(user, inlineOrder());

        assertThatThrownBy(() -> purchaseService.cancelItem(user, purchase, other, 1))
                .isInstanceOf(OrderItemNotFoundException.class);
    }

    // Once the order is being prepared it is too late to drop a line.
    @Test
    void cancelItem_throws_onceTheOrderIsUnderway() {
        UUID user = persistUser();
        UUID book = persistBook("Clean Code", "10.00", 10);
        cartService.addItem(user, book, 2);
        UUID purchase = purchaseService.placeOrder(user, inlineOrder());
        payFor(user, purchase, "20.00");
        purchaseService.prepare(purchase);

        assertThatThrownBy(() -> purchaseService.cancelItem(user, purchase, book, 1))
                .isInstanceOf(IllegalOrderStateException.class);
    }
}
