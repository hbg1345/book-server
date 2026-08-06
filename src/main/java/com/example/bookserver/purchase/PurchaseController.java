package com.example.bookserver.purchase;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.purchase.dto.CancelItemRequest;
import com.example.bookserver.purchase.dto.OrderDetailResponse;
import com.example.bookserver.purchase.dto.OrderSummaryResponse;
import com.example.bookserver.purchase.dto.PaymentIntentResponse;
import com.example.bookserver.purchase.dto.PlaceOrderRequest;
import com.example.bookserver.purchase.dto.PlaceOrderResponse;
import com.example.bookserver.purchase.dto.ShipOrderRequest;

import jakarta.validation.Valid;

/**
 * Order endpoints for the authenticated user. The user's uuid is carried by the JWT
 * and injected via {@link AuthenticationPrincipal}; every operation is scoped to that
 * user, so no one can see or act on another user's orders. Orders are created from the
 * caller's cart and progress PAYMENT_PENDING → ORDERED (on payment); cancellation is
 * only allowed before the order is prepared.
 */
@RestController
@RequestMapping("/api/orders")
public class PurchaseController {

    private final PurchaseService purchaseService;
    private final OrderExpiryScheduler orderExpiryScheduler;

    public PurchaseController(PurchaseService purchaseService,
                             OrderExpiryScheduler orderExpiryScheduler) {
        this.purchaseService = purchaseService;
        this.orderExpiryScheduler = orderExpiryScheduler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceOrderResponse place(@AuthenticationPrincipal UUID userUuid,
                                    @Valid @RequestBody PlaceOrderRequest request) {
        UUID purchaseUuid = purchaseService.placeOrder(userUuid, request);
        // schedule the precise per-order expiry; best-effort, the periodic sweep is the safety net
        orderExpiryScheduler.scheduleExpiry(purchaseUuid);
        return new PlaceOrderResponse(purchaseUuid);
    }

    @GetMapping
    public List<OrderSummaryResponse> myOrders(@AuthenticationPrincipal UUID userUuid) {
        return purchaseService.listMyOrders(userUuid).stream().map(OrderSummaryResponse::from).toList();
    }

    @GetMapping("/{purchaseUuid}")
    public OrderDetailResponse detail(@AuthenticationPrincipal UUID userUuid,
                                      @PathVariable UUID purchaseUuid) {
        return OrderDetailResponse.from(purchaseService.getOrder(userUuid, purchaseUuid));
    }

    /**
     * Open a payment intent for the order. Takes no body: the amount is the server's own order
     * total. The response carries the client secret the frontend confirms the card with; the
     * order only becomes ORDERED once the provider's webhook reports the charge succeeded.
     */
    @PostMapping("/{purchaseUuid}/payment-intent")
    public PaymentIntentResponse openPaymentIntent(@AuthenticationPrincipal UUID userUuid,
                                                   @PathVariable UUID purchaseUuid) {
        return PaymentIntentResponse.from(purchaseService.openPaymentIntent(userUuid, purchaseUuid));
    }

    @PostMapping("/{purchaseUuid}/cancel")
    public void cancel(@AuthenticationPrincipal UUID userUuid, @PathVariable UUID purchaseUuid) {
        purchaseService.cancel(userUuid, purchaseUuid);
    }

    /**
     * Drop some copies of one book from the order, leaving the rest of it live. A paid order has
     * that share of the money returned.
     *
     * <p>Addressed as an item of the order rather than as a flag on the order-level cancel: what
     * is being cancelled is the line, and naming it in the path says so without a request body
     * that means different things depending on which fields are present.
     */
    @PostMapping("/{purchaseUuid}/items/{bookUuid}/cancel")
    public void cancelItem(@AuthenticationPrincipal UUID userUuid,
                           @PathVariable UUID purchaseUuid,
                           @PathVariable UUID bookUuid,
                           @Valid @RequestBody CancelItemRequest request) {
        purchaseService.cancelItem(userUuid, purchaseUuid, bookUuid, request.quantity());
    }

    // --- fulfillment lifecycle (#26). prepare/ship/deliver are admin-only (enforced in
    // SecurityConfig) and act on any order; confirm is the buyer's own action. ---

    @PostMapping("/{purchaseUuid}/prepare")
    public void prepare(@PathVariable UUID purchaseUuid) {
        purchaseService.prepare(purchaseUuid);
    }

    @PostMapping("/{purchaseUuid}/ship")
    public void ship(@PathVariable UUID purchaseUuid, @Valid @RequestBody ShipOrderRequest request) {
        purchaseService.ship(purchaseUuid, request.trackingNumber());
    }

    @PostMapping("/{purchaseUuid}/deliver")
    public void deliver(@PathVariable UUID purchaseUuid) {
        purchaseService.deliver(purchaseUuid);
    }

    @PostMapping("/{purchaseUuid}/confirm")
    public void confirm(@AuthenticationPrincipal UUID userUuid, @PathVariable UUID purchaseUuid) {
        purchaseService.confirm(userUuid, purchaseUuid);
    }

    // buyer returns a delivered order for a refund
    @PostMapping("/{purchaseUuid}/return")
    public void returnOrder(@AuthenticationPrincipal UUID userUuid, @PathVariable UUID purchaseUuid) {
        purchaseService.returnOrder(userUuid, purchaseUuid);
    }
}
