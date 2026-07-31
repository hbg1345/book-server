package com.example.bookserver.purchase.dto;

import java.util.UUID;

/** Response for a placed order: the new purchase_uuid. */
public record PlaceOrderResponse(UUID purchaseUuid) {
}
