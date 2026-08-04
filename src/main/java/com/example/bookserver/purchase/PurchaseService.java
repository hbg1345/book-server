package com.example.bookserver.purchase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import com.example.bookserver.payment.ChargeRequest;
import com.example.bookserver.payment.ChargeResult;
import com.example.bookserver.payment.Payment;
import com.example.bookserver.payment.PaymentAmountMismatchException;
import com.example.bookserver.payment.PaymentGateway;
import com.example.bookserver.payment.PaymentMapper;
import com.example.bookserver.payment.PaymentStatus;
import com.example.bookserver.payment.RefundFailedException;
import com.example.bookserver.payment.RefundRequest;
import com.example.bookserver.payment.RefundResult;
import com.example.bookserver.purchase.dto.PayRequest;
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
     * Charge the order and, on success, advance it {@code PAYMENT_PENDING -> ORDERED}. The
     * charge amount is verified server-side against the order total (a mismatch is rejected
     * before the gateway is touched) and the payment key is used as the idempotency key, so a
     * retry with the same key returns the existing charge instead of charging again. A declined
     * charge records a FAILED payment and leaves the order unpaid. Returns the payment record.
     */
    @Transactional
    public Payment pay(UUID userUuid, UUID purchaseUuid, PayRequest req) {
        PurchaseCurrent current = requireOwnOrder(userUuid, purchaseUuid);

        // idempotent replay: the same payment key resolves to its existing charge, no re-charge
        Payment existing = paymentMapper.findByIdempotencyKey(req.paymentKey());
        if (existing != null) {
            return existing;
        }

        requireState(current, PurchaseState.PAYMENT_PENDING);

        BigDecimal orderTotal = current.getPrice();
        if (req.amount().compareTo(orderTotal) != 0) {
            throw new PaymentAmountMismatchException(orderTotal, req.amount());   // never trust the client
        }

        ChargeResult result = paymentGateway.confirm(new ChargeRequest(
                purchaseUuid, orderTotal, "KRW", req.paymentKey(), req.paymentKey()));

        Payment payment = new Payment(Uuids.newId(), purchaseUuid, req.provider(),
                result.providerTransactionId(), orderTotal,
                result.success() ? PaymentStatus.PAID : PaymentStatus.FAILED,
                req.paymentKey(), null, null);
        paymentMapper.insert(payment);

        if (result.success()) {
            transition(current, PurchaseState.ORDERED);
        }
        return payment;
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
        PurchaseCurrent current = requireOrder(purchaseUuid);
        requireState(current, PurchaseState.ORDERED);
        transition(current, PurchaseState.PREPARING);
    }

    /** Seller/admin: {@code PREPARING -> SHIPPING}, capturing the shipment tracking number. */
    @Transactional
    public void ship(UUID purchaseUuid, String trackingNumber) {
        PurchaseCurrent current = requireOrder(purchaseUuid);
        requireState(current, PurchaseState.PREPARING);
        transition(current, PurchaseState.SHIPPING);
        currentMapper.updateTrackingNumber(purchaseUuid, trackingNumber);
    }

    /** Seller/admin: {@code SHIPPING -> DELIVERED}. */
    @Transactional
    public void deliver(UUID purchaseUuid) {
        PurchaseCurrent current = requireOrder(purchaseUuid);
        requireState(current, PurchaseState.SHIPPING);
        transition(current, PurchaseState.DELIVERED);
    }

    /** Buyer: {@code DELIVERED -> CONFIRMED} (purchase confirmed). Owner-scoped. */
    @Transactional
    public void confirm(UUID userUuid, UUID purchaseUuid) {
        PurchaseCurrent current = requireOwnOrder(userUuid, purchaseUuid);
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
        PurchaseCurrent current = requireOwnOrder(userUuid, purchaseUuid);
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
        PurchaseCurrent current = requireOwnOrder(userUuid, purchaseUuid);
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
        PurchaseCurrent current = currentMapper.findByPurchaseUuid(purchaseUuid);
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
        PurchaseCurrent current = currentMapper.findByPurchaseUuid(purchaseUuid);
        if (current == null || !current.getUserUuid().equals(userUuid)) {
            throw new OrderNotFoundException(purchaseUuid);   // don't reveal others' orders
        }
        return current;
    }

    /** Look up an order by id with no ownership check — for admin/seller fulfillment actions. */
    private PurchaseCurrent requireOrder(UUID purchaseUuid) {
        PurchaseCurrent current = currentMapper.findByPurchaseUuid(purchaseUuid);
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
