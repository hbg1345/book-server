package com.example.bookserver.purchase;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.purchase.dto.OrderDetailResponse;
import com.example.bookserver.purchase.dto.OrderSummaryResponse;
import com.example.bookserver.purchase.dto.PlaceOrderResponse;

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
    public PlaceOrderResponse place(@AuthenticationPrincipal UUID userUuid) {
        UUID purchaseUuid = purchaseService.placeOrder(userUuid);
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
    public void pay(@AuthenticationPrincipal UUID userUuid, @PathVariable UUID purchaseUuid) {
        purchaseService.pay(userUuid, purchaseUuid);
    }

    @PostMapping("/{purchaseUuid}/cancel")
    public void cancel(@AuthenticationPrincipal UUID userUuid, @PathVariable UUID purchaseUuid) {
        purchaseService.cancel(userUuid, purchaseUuid);
    }
}
