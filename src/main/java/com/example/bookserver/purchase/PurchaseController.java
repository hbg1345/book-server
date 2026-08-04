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

import com.example.bookserver.purchase.dto.OrderDetailResponse;
import com.example.bookserver.purchase.dto.OrderSummaryResponse;
import com.example.bookserver.payment.Payment;
import com.example.bookserver.payment.PaymentDeclinedException;
import com.example.bookserver.payment.PaymentStatus;
import com.example.bookserver.purchase.dto.PayRequest;
import com.example.bookserver.purchase.dto.PaymentResponse;
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

    @PostMapping("/{purchaseUuid}/pay")
    public PaymentResponse pay(@AuthenticationPrincipal UUID userUuid, @PathVariable UUID purchaseUuid,
                               @Valid @RequestBody PayRequest request) {
        Payment payment = purchaseService.pay(userUuid, purchaseUuid, request);
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new PaymentDeclinedException(purchaseUuid);   // charge declined -> 402, order stays unpaid
        }
        return PaymentResponse.from(payment);
    }

    @PostMapping("/{purchaseUuid}/cancel")
    public void cancel(@AuthenticationPrincipal UUID userUuid, @PathVariable UUID purchaseUuid) {
        purchaseService.cancel(userUuid, purchaseUuid);
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
}
