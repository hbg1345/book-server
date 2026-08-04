package com.example.bookserver.purchase.dto;

import com.example.bookserver.purchase.OrderAddress;

/** The delivery address snapshotted onto an order (copied values, not a link to the address book). */
public record DeliveryAddressResponse(
        String recipient,
        String phone,
        String country,
        String roadAddress,
        String detailAddress,
        String postalCode) {

    public static DeliveryAddressResponse from(OrderAddress a) {
        if (a == null) {
            return null;
        }
        return new DeliveryAddressResponse(a.getRecipient(), a.getPhone(), a.getCountry(),
                a.getRoadAddress(), a.getDetailAddress(), a.getPostalCode());
    }
}
