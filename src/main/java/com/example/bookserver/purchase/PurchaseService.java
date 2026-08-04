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

    private final PurchaseCurrentMapper currentMapper;
    private final PurchaseHistoryMapper historyMapper;
    private final PurchaseBookHistoryMapper bookHistoryMapper;
    private final OrderAddressMapper orderAddressMapper;
    private final AddressMapper addressMapper;
    private final BookMapper bookMapper;
    private final CartService cartService;

    public PurchaseService(PurchaseCurrentMapper currentMapper,
                           PurchaseHistoryMapper historyMapper,
                           PurchaseBookHistoryMapper bookHistoryMapper,
                           OrderAddressMapper orderAddressMapper,
                           AddressMapper addressMapper,
                           BookMapper bookMapper,
                           CartService cartService) {
        this.currentMapper = currentMapper;
        this.historyMapper = historyMapper;
        this.bookHistoryMapper = bookHistoryMapper;
        this.orderAddressMapper = orderAddressMapper;
        this.addressMapper = addressMapper;
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
        // Resolve (and own-check / format-validate) the address before touching stock, so a
        // bad or not-owned address fails fast rather than reserving inventory then rolling back.
        OrderAddress delivery = resolveDeliveryAddress(userUuid, req);

        List<CartItemView> cart = cartService.listMyCart(userUuid);
        if (cart.isEmpty()) {
            throw new EmptyCartException();
        }
        for (CartItemView item : cart) {
            if (bookMapper.decrementInventory(item.getBookUuid(), item.getQuantity()) == 0) {
                throw new InsufficientInventoryException(item.getBookUuid());
            }
        }
        UUID purchaseUuid = Uuids.newId();
        BigDecimal total = cart.stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BookLine> lines = cart.stream()
                .map(i -> new BookLine(i.getBookUuid(), i.getQuantity(), i.getPrice()))
                .toList();
        recordEvent(purchaseUuid, userUuid, PurchaseState.PAYMENT_PENDING, total, lines, LocalDateTime.now());
        delivery.setPurchaseUuid(purchaseUuid);   // purchase_current row now exists (recordEvent upserted it)
        orderAddressMapper.insert(delivery);
        cartService.clear(userUuid);
        return purchaseUuid;
    }

    /**
     * Turn the request's address choice into an immutable snapshot: either a saved address
     * the user owns (404 if it is not theirs / missing) or a one-off inline address (postal
     * code format-validated per country). The DTO guarantees exactly one of the two is set.
     * purchase_uuid is filled in by the caller once the order id exists.
     */
    private OrderAddress resolveDeliveryAddress(UUID userUuid, PlaceOrderRequest req) {
        if (req.addressUuid() != null) {
            Address a = addressMapper.findByIdAndUser(req.addressUuid(), userUuid);
            if (a == null) {
                throw new AddressNotFoundException(req.addressUuid());   // don't reveal others' addresses
            }
            return new OrderAddress(null, a.getRecipient(), a.getPhone(), a.getCountry(),
                    a.getRoadAddress(), a.getDetailAddress(), a.getPostalCode(), null);
        }
        PlaceOrderRequest.InlineAddress in = req.address();
        String country = PostalCodes.normalizeCountry(in.country());
        PostalCodes.validate(country, in.postalCode());
        return new OrderAddress(null, in.recipient(), in.phone(), country,
                in.roadAddress(), in.detailAddress(), in.postalCode(), null);
    }

    /** Confirm payment: {@code PAYMENT_PENDING -> ORDERED}. */
    @Transactional
    public void pay(UUID userUuid, UUID purchaseUuid) {
        PurchaseCurrent current = requireOwnOrder(userUuid, purchaseUuid);
        if (current.getPurchaseState() != PurchaseState.PAYMENT_PENDING) {
            throw new IllegalOrderStateException("Order is not awaiting payment: " + current.getPurchaseState());
        }
        transition(current, PurchaseState.ORDERED);
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

    /** Cancel an order (only before it is prepared) and return its reserved stock. */
    @Transactional
    public void cancel(UUID userUuid, UUID purchaseUuid) {
        PurchaseCurrent current = requireOwnOrder(userUuid, purchaseUuid);
        if (!CANCELLABLE.contains(current.getPurchaseState())) {
            throw new IllegalOrderStateException("Order can no longer be cancelled: " + current.getPurchaseState());
        }
        restoreStockAndCancel(current);
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
        for (PurchaseBookHistory book : bookHistoryMapper.findByHistoryUuid(current.getHistoryUuid())) {
            bookMapper.incrementInventory(book.getBookUuid(), book.getQuantity());
        }
        transition(current, PurchaseState.CANCELLED);
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

    /** A book line carried through a state event: which book, how many, at what unit price. */
    private record BookLine(UUID bookUuid, int quantity, BigDecimal price) {
    }
}
