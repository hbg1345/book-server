package com.example.bookserver.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.example.bookserver.purchase.OrderDetail;
import com.example.bookserver.purchase.PurchaseState;

/** Full order view: current header, tracking number, delivery address, books, and the state timeline. */
public record OrderDetailResponse(
        UUID purchaseUuid,
        PurchaseState purchaseState,
        BigDecimal price,
        LocalDateTime updatedAt,
        String trackingNumber,
        DeliveryAddressResponse deliveryAddress,
        List<OrderItemResponse> items,
        List<OrderStateEventResponse> history) {

    public static OrderDetailResponse from(OrderDetail detail) {
        var current = detail.current();
        return new OrderDetailResponse(
                current.getPurchaseUuid(),
                current.getPurchaseState(),
                current.getPrice(),
                current.getUpdatedAt(),
                current.getTrackingNumber(),
                DeliveryAddressResponse.from(detail.delivery()),
                detail.items().stream().map(OrderItemResponse::from).toList(),
                detail.history().stream().map(OrderStateEventResponse::from).toList());
    }
}
