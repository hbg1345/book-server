package com.example.bookserver.address.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.example.bookserver.address.Address;

/** One saved address as returned by the API. */
public record AddressResponse(
        UUID addressUuid,
        String alias,
        String recipient,
        String phone,
        String country,
        String roadAddress,
        String detailAddress,
        String postalCode,
        boolean defaultAddress,
        LocalDateTime createdAt) {

    public static AddressResponse from(Address a) {
        return new AddressResponse(
                a.getAddressUuid(),
                a.getAlias(),
                a.getRecipient(),
                a.getPhone(),
                a.getCountry(),
                a.getRoadAddress(),
                a.getDetailAddress(),
                a.getPostalCode(),
                a.isDefaultAddress(),
                a.getCreatedAt());
    }
}
