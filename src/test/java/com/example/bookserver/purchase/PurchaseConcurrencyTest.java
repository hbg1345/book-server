package com.example.bookserver.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.book.Book;
import com.example.bookserver.book.BookMapper;
import com.example.bookserver.cart.CartService;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.payment.IntentRequest;
import com.example.bookserver.payment.IntentResult;
import com.example.bookserver.payment.OpenedPayment;
import com.example.bookserver.payment.PaymentGateway;
import com.example.bookserver.payment.RefundRequest;
import com.example.bookserver.payment.RefundResult;
import com.example.bookserver.purchase.dto.PlaceOrderRequest;
import com.example.bookserver.purchase.dto.PlaceOrderRequest.InlineAddress;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the single-threaded tests cannot see: whether the order paths still hold when several
 * transactions touch the same book at once.
 *
 * <p>Unlike {@link PurchaseServiceTest}, this runs on {@code @SpringBootTest} with the real
 * container-managed beans. That matters twice over: {@code @Transactional} is only honoured on
 * a proxied bean (the other test constructs {@link PurchaseService} with {@code new}, so every
 * call there runs outside a transaction), and the test method itself must not be wrapped in a
 * rolled-back transaction, or the worker threads would not see the fixtures at all.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
// One connection per concurrent transaction: with Hikari's default of 10 the workers would
// queue on the pool instead of contending on the row, and the race under test would not run.
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=25")
class PurchaseConcurrencyTest {

    /**
     * The provider stands in for a real one in the way that matters here: its refund takes long
     * enough to hold the transaction open. A gateway that returns instantly would close the race
     * window by accident and let a broken lock pass.
     */
    @MockitoBean
    private PaymentGateway paymentGateway;

    @BeforeEach
    void stubGateway() {
        when(paymentGateway.provider()).thenReturn("fake");
        when(paymentGateway.openIntent(any(IntentRequest.class))).thenAnswer(invocation -> {
            IntentRequest request = invocation.getArgument(0);
            return IntentResult.opened("txn-" + request.purchaseUuid(), "secret");
        });
        when(paymentGateway.refund(any(RefundRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(300);   // a real provider round-trip, and the width of the race window
            return RefundResult.refunded("refund-txn");
        });
    }

    @Autowired
    private PurchaseService purchaseService;
    @Autowired
    private CartService cartService;
    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private UserMapper userMapper;

    private static PlaceOrderRequest inlineOrder() {
        return new PlaceOrderRequest(null,
                new InlineAddress("Jane Doe", "010-1234-5678", "KR", "123 Sejong-daero", "5F", "06236"));
    }

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

    private UUID persistBook(int inventory) {
        UUID bookUuid = Uuids.newId();
        Book book = new Book();
        book.setBookUuid(bookUuid);
        book.setBookTitle("Clean Code");
        book.setBookDescription("desc");
        book.setPrice(new BigDecimal("10.00"));
        book.setPublishDate(LocalDate.of(2021, 1, 1));
        book.setPublisher("Wikibooks");
        book.setInventory(inventory);
        bookMapper.insert(book);
        return bookUuid;
    }

    /**
     * Run every task at once and return their results. The latch is the point: submitting to a
     * pool only makes the tasks eligible to run, and threads that start milliseconds apart
     * serialise on their own rather than contending, which is how a concurrency test quietly
     * stops testing concurrency.
     */
    private <T> List<T> runAtOnce(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Twenty buyers, ten copies, one book. Exactly ten orders may exist afterwards and the
     * inventory must land on zero — never below it, and never with an eleventh buyer holding
     * an order for stock that was not there.
     *
     * <p>This is the case a read-then-write would get wrong: every buyer reads "10 left" before
     * any of them writes, and all twenty conclude they may proceed.
     */
    @Test
    void concurrentOrders_neverOversellTheSameBook() throws Exception {
        int stock = 10;
        int buyers = 20;
        UUID bookUuid = persistBook(stock);

        List<Callable<Boolean>> orders = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            UUID userUuid = persistUser();
            cartService.addItem(userUuid, bookUuid, 1);
            orders.add(() -> {
                try {
                    purchaseService.placeOrder(userUuid, inlineOrder());
                    return true;
                } catch (InsufficientInventoryException e) {
                    return false;   // the expected way to lose the race
                }
            });
        }

        List<Boolean> results = runAtOnce(orders);

        long placed = results.stream().filter(Boolean::booleanValue).count();
        assertThat(placed).isEqualTo(stock);
        assertThat(bookMapper.findById(bookUuid).getInventory()).isZero();
    }

    /**
     * A cancel racing the unpaid-order sweeper. Both read the order as PAYMENT_PENDING, both
     * decide it may be cancelled, and both hand the stock back — inventing a copy that was
     * never returned.
     *
     * <p>The pairing is not contrived: {@link UnpaidOrderSweeper} fires on a timer, so it can
     * land on exactly the order a user is cancelling by hand.
     *
     * <p>Repeated, because a race that reproduces one run in five is still a bug that reaches
     * production; a single attempt would pass often enough to be useless as a regression test.
     */
    @Test
    void concurrentCancelAndExpiry_restoreStockOnlyOnce() throws Exception {
        int stock = 10;
        int ordered = 2;

        for (int attempt = 0; attempt < 20; attempt++) {
            UUID bookUuid = persistBook(stock);
            UUID userUuid = persistUser();
            cartService.addItem(userUuid, bookUuid, ordered);
            UUID purchaseUuid = purchaseService.placeOrder(userUuid, inlineOrder());
            assertThat(bookMapper.findById(bookUuid).getInventory()).isEqualTo(stock - ordered);

            runAtOnce(List.<Callable<Void>>of(
                    () -> cancelQuietly(userUuid, purchaseUuid),
                    () -> cancelQuietly(userUuid, purchaseUuid),
                    () -> expireQuietly(purchaseUuid),
                    () -> expireQuietly(purchaseUuid)));

            assertThat(bookMapper.findById(bookUuid).getInventory())
                    .as("attempt %d: the reservation is released once, however many cancels arrive", attempt)
                    .isEqualTo(stock);
        }
    }

    /**
     * Two cancels of a <em>paid</em> order. The refund is a server-to-server call made inside the
     * transaction, so the window between reading the state and writing the result is as wide as
     * the provider is slow — hundreds of milliseconds, not the microseconds of the unpaid path.
     * A user double-clicking cancel is enough to land inside it.
     *
     * <p>What is at stake here is money, not only stock: two refunds mean the customer is paid
     * back twice for one order, and the second refund is not something the shop can undo.
     */
    @Test
    void concurrentCancelsOfAPaidOrder_refundOnlyOnce() throws Exception {
        int stock = 10;
        int ordered = 2;
        UUID bookUuid = persistBook(stock);
        UUID userUuid = persistUser();
        cartService.addItem(userUuid, bookUuid, ordered);
        UUID purchaseUuid = purchaseService.placeOrder(userUuid, inlineOrder());

        // drive it to ORDERED the way the provider's webhook does
        OpenedPayment opened = purchaseService.openPaymentIntent(userUuid, purchaseUuid);
        purchaseService.markPaymentSucceeded(opened.payment().getProviderTxnId(), new BigDecimal("20.00"));
        assertThat(bookMapper.findById(bookUuid).getInventory()).isEqualTo(stock - ordered);

        runAtOnce(List.<Callable<Void>>of(
                () -> cancelQuietly(userUuid, purchaseUuid),
                () -> cancelQuietly(userUuid, purchaseUuid)));

        verify(paymentGateway, times(1)).refund(any(RefundRequest.class));
        assertThat(bookMapper.findById(bookUuid).getInventory()).isEqualTo(stock);
    }

    /** Losing a cancel race is legitimate: the order is already cancelled by the winner. */
    private Void cancelQuietly(UUID userUuid, UUID purchaseUuid) {
        try {
            purchaseService.cancel(userUuid, purchaseUuid);
        } catch (IllegalOrderStateException expected) {
            // the winner already moved it out of PAYMENT_PENDING
        }
        return null;
    }

    private Void expireQuietly(UUID purchaseUuid) {
        purchaseService.expireUnpaidOrder(purchaseUuid);   // documented as a no-op when not pending
        return null;
    }
}
