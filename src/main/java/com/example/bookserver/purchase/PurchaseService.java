package com.example.bookserver.purchase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.bookserver.address.Address;
import com.example.bookserver.address.AddressMapper;
import com.example.bookserver.address.AddressNotFoundException;
import com.example.bookserver.address.PostalCodes;
import com.example.bookserver.book.BookMapper;
import com.example.bookserver.cart.CartItemView;
import com.example.bookserver.cart.CartService;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.payment.IntentRequest;
import com.example.bookserver.payment.IntentResult;
import com.example.bookserver.payment.OpenedPayment;
import com.example.bookserver.payment.Payment;
import com.example.bookserver.payment.PaymentAmountMismatchException;
import com.example.bookserver.payment.PaymentGateway;
import com.example.bookserver.payment.PaymentIntentFailedException;
import com.example.bookserver.payment.PaymentMapper;
import com.example.bookserver.payment.PaymentStatus;
import com.example.bookserver.payment.RefundFailedException;
import com.example.bookserver.payment.RefundRequest;
import com.example.bookserver.payment.RefundResult;
import com.example.bookserver.purchase.dto.PlaceOrderRequest;

/**
 * Orders on top of the append-only purchase tables. Each state change appends one
 * {@link PurchaseHistory} event plus its per-book {@link PurchaseBookHistory} rows,
 * then moves the {@link PurchaseCurrent} head pointer — so the current state is a
 * point lookup while the full timeline stays auditable. Stock is reserved from
 * {@code book.inventory} when the order is placed and given back if it is cancelled.
 */
@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    /** An order may only be cancelled before it starts being prepared. */
    private static final Set<PurchaseState> CANCELLABLE =
            EnumSet.of(PurchaseState.PAYMENT_PENDING, PurchaseState.ORDERED);

    /** An order may be returned (for a refund) once it has been delivered. */
    private static final Set<PurchaseState> RETURNABLE =
            EnumSet.of(PurchaseState.DELIVERED, PurchaseState.CONFIRMED);

    private final PurchaseCurrentMapper currentMapper;
    private final PurchaseHistoryMapper historyMapper;
    private final PurchaseBookHistoryMapper bookHistoryMapper;
    private final OrderAddressMapper orderAddressMapper;
    private final AddressMapper addressMapper;
    private final PaymentMapper paymentMapper;
    private final PaymentGateway paymentGateway;
    private final BookMapper bookMapper;
    private final CartService cartService;

    public PurchaseService(PurchaseCurrentMapper currentMapper,
                           PurchaseHistoryMapper historyMapper,
                           PurchaseBookHistoryMapper bookHistoryMapper,
                           OrderAddressMapper orderAddressMapper,
                           AddressMapper addressMapper,
                           PaymentMapper paymentMapper,
                           PaymentGateway paymentGateway,
                           BookMapper bookMapper,
                           CartService cartService) {
        this.currentMapper = currentMapper;
        this.historyMapper = historyMapper;
        this.bookHistoryMapper = bookHistoryMapper;
        this.orderAddressMapper = orderAddressMapper;
        this.addressMapper = addressMapper;
        this.paymentMapper = paymentMapper;
        this.paymentGateway = paymentGateway;
        this.bookMapper = bookMapper;
        this.cartService = cartService;
    }

    /**
     * Place an order from the user's cart: snapshot the chosen delivery address, reserve
     * stock for every line, record the order as {@code PAYMENT_PENDING}, and empty the cart.
     * If any book is short on stock the whole thing rolls back. Returns the new purchase_uuid.
     */
    @Transactional
    public UUID placeOrder(UUID userUuid, PlaceOrderRequest req) {
        // The order id has no dependency on anything, so mint it up front — that lets the
        // delivery snapshot be built complete (no half-initialized row patched in later).
        UUID purchaseUuid = Uuids.newId();
        // Resolve (and own-check / format-validate) the address before touching stock, so a
        // bad or not-owned address fails fast rather than reserving inventory then rolling back.
        OrderAddress delivery = resolveDeliveryAddress(purchaseUuid, userUuid, req);

        List<CartItemView> cart = cartService.listMyCart(userUuid);
        if (cart.isEmpty()) {
            throw new EmptyCartException();
        }
        for (CartItemView item : cart) {
            if (bookMapper.decrementInventory(item.getBookUuid(), item.getQuantity()) == 0) {
                throw new InsufficientInventoryException(item.getBookUuid());
            }
        }
        BigDecimal total = cart.stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BookLine> lines = cart.stream()
                .map(i -> new BookLine(i.getBookUuid(), i.getQuantity(), i.getPrice()))
                .toList();
        recordEvent(purchaseUuid, userUuid, PurchaseState.PAYMENT_PENDING, total, lines, LocalDateTime.now());
        orderAddressMapper.insert(delivery);   // purchase_current row now exists (recordEvent upserted it)
        cartService.clear(userUuid);
        return purchaseUuid;
    }

    /**
     * Build the immutable delivery snapshot for {@code purchaseUuid}: either a saved address the
     * user owns (404 if it is not theirs / missing) or a one-off inline address (postal code
     * format-validated per country). The DTO guarantees exactly one of the two is set.
     */
    private OrderAddress resolveDeliveryAddress(UUID purchaseUuid, UUID userUuid, PlaceOrderRequest req) {
        if (req.addressUuid() != null) {
            Address a = addressMapper.findByIdAndUser(req.addressUuid(), userUuid);
            if (a == null) {
                throw new AddressNotFoundException(req.addressUuid());   // don't reveal others' addresses
            }
            return new OrderAddress(purchaseUuid, a.getRecipient(), a.getPhone(), a.getCountry(),
                    a.getRoadAddress(), a.getDetailAddress(), a.getPostalCode(),
                    req.addressUuid(), null);   // breadcrumb to the saved address it was copied from
        }
        PlaceOrderRequest.InlineAddress in = req.address();
        String country = PostalCodes.normalizeCountry(in.country());
        PostalCodes.validate(country, in.postalCode());
        return new OrderAddress(purchaseUuid, in.recipient(), in.phone(), country,
                in.roadAddress(), in.detailAddress(), in.postalCode(), null, null);   // one-off: no source
    }

    /**
     * Open a payment intent for the order and return it with the provider's client secret, which
     * the frontend uses to confirm the card directly with the provider.
     *
     * <p>The order does NOT advance here: the card is authorized between the browser and the
     * provider, and the result reaches us as a webhook, which is what moves
     * {@code PAYMENT_PENDING -> ORDERED}. The amount is the server's own order total — the client
     * never supplies one, so there is nothing to tamper with. The idempotency key is order-scoped,
     * so a retry replays the same intent (and the same secret) rather than opening a second one.
     */
    @Transactional
    public OpenedPayment openPaymentIntent(UUID userUuid, UUID purchaseUuid) {
        PurchaseCurrent current = requireOwnOrderForUpdate(userUuid, purchaseUuid);
        requireState(current, PurchaseState.PAYMENT_PENDING);

        String idempotencyKey = intentKey(purchaseUuid);
        IntentResult result = paymentGateway.openIntent(
                new IntentRequest(purchaseUuid, current.getPrice(), idempotencyKey));
        if (!result.success()) {
            throw new PaymentIntentFailedException(purchaseUuid, result.failureReason());
        }

        // one payment row per order: a retry finds the row the first call inserted
        Payment payment = paymentMapper.findByIdempotencyKey(idempotencyKey);
        if (payment == null) {
            payment = new Payment(Uuids.newId(), purchaseUuid, paymentGateway.provider(),
                    result.providerIntentId(), current.getPrice(), PaymentStatus.PENDING,
                    idempotencyKey, null, null);
            paymentMapper.insert(payment);
        }
        return new OpenedPayment(payment, result.clientSecret());
    }

    /** Order-scoped idempotency key: the dedup unit for both the provider and our payment row. */
    private static String intentKey(UUID purchaseUuid) {
        return "order-" + purchaseUuid;
    }

    /**
     * The provider reports a confirmed charge: mark the payment PAID and advance the order
     * {@code PAYMENT_PENDING -> ORDERED}. This is the only path that makes an order paid — the
     * browser's word is never taken for it.
     *
     * <p>Deliberately idempotent, because providers re-deliver webhooks: an already-PAID payment
     * is a no-op, and the order only transitions if it is still awaiting payment. An unknown
     * intent is ignored rather than failing, so a webhook for something we never opened (or that
     * was already cleaned up) does not wedge the provider's retry loop. The charged amount is
     * re-checked against the order total here — the last point where a mismatch can be caught.
     */
    @Transactional
    public void markPaymentSucceeded(String providerIntentId, BigDecimal chargedAmount) {
        Payment payment = paymentMapper.findByProviderTxnId(providerIntentId);
        if (payment == null) {
            // not an error we can act on, but it means the provider charged something we have no
            // record of — worth a loud line rather than a silent return
            log.warn("Ignoring succeeded-payment webhook for unknown intent {}", providerIntentId);
            return;
        }
        if (payment.getStatus() == PaymentStatus.PAID) {
            log.debug("Payment {} is already PAID; re-delivered webhook ignored", providerIntentId);
            return;
        }
        if (chargedAmount.compareTo(payment.getAmount()) != 0) {
            throw new PaymentAmountMismatchException(payment.getAmount(), chargedAmount);
        }
        paymentMapper.updateStatus(payment.getPaymentUuid(), PaymentStatus.PAID);

        PurchaseCurrent current = currentMapper.findByPurchaseUuidForUpdate(payment.getPurchaseUuid());
        if (current != null && current.getPurchaseState() == PurchaseState.PAYMENT_PENDING) {
            transition(current, PurchaseState.ORDERED);
        }
    }

    /**
     * The provider reports a failed charge: record it for audit and leave the order at
     * {@code PAYMENT_PENDING} so the customer can retry on the same intent (or let it expire).
     * Idempotent and tolerant of unknown intents, for the same reasons as
     * {@link #markPaymentSucceeded}.
     */
    @Transactional
    public void markPaymentFailed(String providerIntentId) {
        Payment payment = paymentMapper.findByProviderTxnId(providerIntentId);
        if (payment == null) {
            log.warn("Ignoring failed-payment webhook for unknown intent {}", providerIntentId);
            return;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            // a "failed" arriving after the payment already settled is out-of-order delivery, not
            // a reason to undo it — but it should be visible if it starts happening
            log.warn("Ignoring failed-payment webhook for {}: payment is already {}",
                    providerIntentId, payment.getStatus());
            return;
        }
        paymentMapper.updateStatus(payment.getPaymentUuid(), PaymentStatus.FAILED);
    }

    /**
     * The provider reports that a refund it previously accepted has been reversed.
     *
     * <p>Refunds are issued synchronously and the order is moved to REFUNDED there and then, but
     * the money can still fail to land afterwards — a closed card, a bank that rejects the credit.
     * That arrives here, potentially days later.
     *
     * <p>Nothing is rolled back. The buyer has been told the order is refunded and their stock is
     * already back on the shelf; reversing either would be a second wrong on top of the first. The
     * payment row records the truth instead, so reconciliation can find the orders where money is
     * owed, and the log says so loudly because no automatic path fixes this.
     */
    @Transactional
    public void markRefundFailed(String providerIntentId, String reason) {
        Payment payment = paymentMapper.findByProviderTxnId(providerIntentId);
        if (payment == null) {
            log.warn("Ignoring failed-refund webhook for unknown intent {}", providerIntentId);
            return;
        }
        if (payment.getStatus() == PaymentStatus.REFUND_FAILED) {
            log.debug("Payment {} is already REFUND_FAILED; re-delivered webhook ignored", providerIntentId);
            return;
        }
        paymentMapper.updateStatus(payment.getPaymentUuid(), PaymentStatus.REFUND_FAILED);
        log.error("Refund reversed for order {} (intent {}, {} {}): the buyer has NOT been repaid "
                        + "and this needs a manual refund",
                payment.getPurchaseUuid(), providerIntentId, payment.getAmount(),
                reason == null ? "no reason given" : reason);
    }

    /** Current state of all of the user's orders, newest first. */
    public List<PurchaseCurrent> listMyOrders(UUID userUuid) {
        return currentMapper.findByUserUuid(userUuid);
    }

    /** One order: its current header, its delivery address, the books in it, and the full state timeline. */
    public OrderDetail getOrder(UUID userUuid, UUID purchaseUuid) {
        PurchaseCurrent current = requireOwnOrder(userUuid, purchaseUuid);
        OrderAddress delivery = orderAddressMapper.findByPurchaseUuid(purchaseUuid);
        List<OrderBookItem> items = bookHistoryMapper.findItemsWithBookByHistoryUuid(current.getHistoryUuid());
        List<PurchaseHistory> history = historyMapper.findAllByPurchaseUuid(purchaseUuid);
        return new OrderDetail(current, delivery, items, history);
    }

    /** Seller/admin: {@code ORDERED -> PREPARING}. Not owner-scoped — admin acts on any order. */
    @Transactional
    public void prepare(UUID purchaseUuid) {
        PurchaseCurrent current = requireOrderForUpdate(purchaseUuid);
        requireState(current, PurchaseState.ORDERED);
        transition(current, PurchaseState.PREPARING);
    }

    /** Seller/admin: {@code PREPARING -> SHIPPING}, capturing the shipment tracking number. */
    @Transactional
    public void ship(UUID purchaseUuid, String trackingNumber) {
        PurchaseCurrent current = requireOrderForUpdate(purchaseUuid);
        requireState(current, PurchaseState.PREPARING);
        transition(current, PurchaseState.SHIPPING);
        currentMapper.updateTrackingNumber(purchaseUuid, trackingNumber);
    }

    /** Seller/admin: {@code SHIPPING -> DELIVERED}. */
    @Transactional
    public void deliver(UUID purchaseUuid) {
        PurchaseCurrent current = requireOrderForUpdate(purchaseUuid);
        requireState(current, PurchaseState.SHIPPING);
        transition(current, PurchaseState.DELIVERED);
    }

    /** Buyer: {@code DELIVERED -> CONFIRMED} (purchase confirmed). Owner-scoped. */
    @Transactional
    public void confirm(UUID userUuid, UUID purchaseUuid) {
        PurchaseCurrent current = requireOwnOrderForUpdate(userUuid, purchaseUuid);
        requireState(current, PurchaseState.DELIVERED);
        transition(current, PurchaseState.CONFIRMED);
    }

    /**
     * Cancel an order (only before it is prepared). An unpaid order simply releases its reserved
     * stock and becomes CANCELLED; a paid one (ORDERED) is refunded instead (REFUND_REQUESTED ->
     * REFUNDED) and its stock restored.
     */
    @Transactional
    public void cancel(UUID userUuid, UUID purchaseUuid) {
        PurchaseCurrent current = requireOwnOrderForUpdate(userUuid, purchaseUuid);
        if (!CANCELLABLE.contains(current.getPurchaseState())) {
            throw new IllegalOrderStateException("Order can no longer be cancelled: " + current.getPurchaseState());
        }
        if (current.getPurchaseState() == PurchaseState.PAYMENT_PENDING) {
            restoreStockAndCancel(current);   // never charged -> just release the reservation
        } else {
            refundAndRestore(current);        // ORDERED -> paid -> refund
        }
    }

    /** Buyer returns a delivered order for a refund: {@code DELIVERED/CONFIRMED -> REFUNDED}. Owner-scoped. */
    @Transactional
    public void returnOrder(UUID userUuid, UUID purchaseUuid) {
        PurchaseCurrent current = requireOwnOrderForUpdate(userUuid, purchaseUuid);
        if (!RETURNABLE.contains(current.getPurchaseState())) {
            throw new IllegalOrderStateException("Order cannot be returned: " + current.getPurchaseState());
        }
        refundAndRestore(current);
    }

    /** Purchase uuids still awaiting payment since before {@code cutoff}. */
    public List<UUID> findUnpaidOrdersBefore(LocalDateTime cutoff) {
        return currentMapper.findPurchaseUuidsByStateOlderThan(PurchaseState.PAYMENT_PENDING, cutoff);
    }

    /**
     * System-initiated expiry of one unpaid order: cancel it and release its reserved
     * stock, reusing the normal cancel path. Re-reads the state under the transaction and
     * no-ops unless it is still {@code PAYMENT_PENDING}, so an order paid (or cancelled)
     * between the sweep's scan and this call is never double-processed.
     */
    @Transactional
    public void expireUnpaidOrder(UUID purchaseUuid) {
        PurchaseCurrent current = currentMapper.findByPurchaseUuidForUpdate(purchaseUuid);
        if (current == null || current.getPurchaseState() != PurchaseState.PAYMENT_PENDING) {
            return;
        }
        restoreStockAndCancel(current);
    }

    /** Give back the reserved stock for every line and append a {@code CANCELLED} event. */
    private void restoreStockAndCancel(PurchaseCurrent current) {
        restoreStock(current);
        transition(current, PurchaseState.CANCELLED);
    }

    /** Give back the reserved/sold stock for every line of the order's current event. */
    private void restoreStock(PurchaseCurrent current) {
        for (PurchaseBookHistory book : bookHistoryMapper.findByHistoryUuid(current.getHistoryUuid())) {
            bookMapper.incrementInventory(book.getBookUuid(), book.getQuantity());
        }
    }

    /**
     * Refund a paid order and give its stock back. The gateway is called first, so a failure
     * throws before anything is written and the order is left exactly as it was (retryable). On
     * success the timeline records {@code REFUND_REQUESTED -> REFUNDED}, the payment is marked
     * REFUNDED, and stock is restored.
     */
    private void refundAndRestore(PurchaseCurrent current) {
        Payment payment = paymentMapper.findByPurchaseUuid(current.getPurchaseUuid());

        RefundResult result = paymentGateway.refund(new RefundRequest(
                payment.getProviderTxnId(), current.getPrice(), payment.getIdempotencyKey() + "-refund"));
        if (!result.success()) {
            throw new RefundFailedException(current.getPurchaseUuid());   // nothing written yet
        }

        transition(current, PurchaseState.REFUND_REQUESTED);
        paymentMapper.updateStatus(payment.getPaymentUuid(), PaymentStatus.REFUNDED);
        PurchaseCurrent afterRequest = currentMapper.findByPurchaseUuid(current.getPurchaseUuid());
        restoreStock(afterRequest);
        transition(afterRequest, PurchaseState.REFUNDED);
    }

    /** Append a new state event carrying the same books (and total) as the current one. */
    private void transition(PurchaseCurrent current, PurchaseState newState) {
        List<BookLine> lines = bookHistoryMapper.findByHistoryUuid(current.getHistoryUuid()).stream()
                .map(b -> new BookLine(b.getBookUuid(), b.getQuantity(), b.getPrice()))
                .toList();
        recordEvent(current.getPurchaseUuid(), current.getUserUuid(), newState,
                current.getPrice(), lines, LocalDateTime.now());
    }

    /** One state change: append the order event + its per-book rows, then move the head pointer. */
    private void recordEvent(UUID purchaseUuid, UUID userUuid, PurchaseState state,
                             BigDecimal total, List<BookLine> lines, LocalDateTime at) {
        UUID historyUuid = Uuids.newId();
        historyMapper.insert(new PurchaseHistory(historyUuid, purchaseUuid, userUuid, state, total, at));
        for (BookLine line : lines) {
            bookHistoryMapper.insert(new PurchaseBookHistory(
                    historyUuid, line.bookUuid(), state, line.quantity(), line.price(), at));
        }
        currentMapper.upsert(new PurchaseCurrent(purchaseUuid, userUuid, historyUuid, state, total, at));
    }

    private PurchaseCurrent requireOwnOrder(UUID userUuid, UUID purchaseUuid) {
        return ownOrder(currentMapper.findByPurchaseUuid(purchaseUuid), userUuid, purchaseUuid);
    }

    /**
     * As {@link #requireOwnOrder}, but locks the order row for the rest of the transaction.
     * Every caller that goes on to change the order's state uses this one: the state check
     * that follows is made in Java, so without the lock a concurrent transaction can pass the
     * same check and apply the same transition twice.
     */
    private PurchaseCurrent requireOwnOrderForUpdate(UUID userUuid, UUID purchaseUuid) {
        return ownOrder(currentMapper.findByPurchaseUuidForUpdate(purchaseUuid), userUuid, purchaseUuid);
    }

    private PurchaseCurrent ownOrder(PurchaseCurrent current, UUID userUuid, UUID purchaseUuid) {
        if (current == null || !current.getUserUuid().equals(userUuid)) {
            throw new OrderNotFoundException(purchaseUuid);   // don't reveal others' orders
        }
        return current;
    }

    /** Look up an order by id with no ownership check — for admin/seller fulfillment actions. */
    private PurchaseCurrent requireOrder(UUID purchaseUuid) {
        return existingOrder(currentMapper.findByPurchaseUuid(purchaseUuid), purchaseUuid);
    }

    /** As {@link #requireOrder}, locked for a caller that is about to change the state. */
    private PurchaseCurrent requireOrderForUpdate(UUID purchaseUuid) {
        return existingOrder(currentMapper.findByPurchaseUuidForUpdate(purchaseUuid), purchaseUuid);
    }

    private PurchaseCurrent existingOrder(PurchaseCurrent current, UUID purchaseUuid) {
        if (current == null) {
            throw new OrderNotFoundException(purchaseUuid);
        }
        return current;
    }

    /** Guard a transition's precondition: the order must currently be in {@code expected}. */
    private void requireState(PurchaseCurrent current, PurchaseState expected) {
        if (current.getPurchaseState() != expected) {
            throw new IllegalOrderStateException(
                    "Order is " + current.getPurchaseState() + ", expected " + expected);
        }
    }

    /** A book line carried through a state event: which book, how many, at what unit price. */
    private record BookLine(UUID bookUuid, int quantity, BigDecimal price) {
    }
}
