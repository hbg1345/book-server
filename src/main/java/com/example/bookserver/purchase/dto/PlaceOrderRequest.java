package com.example.bookserver.purchase.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for placing an order (POST /api/orders). The order ships to EITHER a saved address
 * picked from the book ({@code addressUuid}) OR a one-off {@code address} supplied inline —
 * exactly one of the two. Whichever is used, its values are snapshotted onto the order, so
 * later edits to a saved address never change a past order. The inline address mirrors the
 * address-book shape (minus alias/default); postal_code format is validated per country in
 * the service.
 */
public record PlaceOrderRequest(
        UUID addressUuid,
        @Valid InlineAddress address) {

    @AssertTrue(message = "provide exactly one of addressUuid or address")
    public boolean isExactlyOneAddressSource() {
        return (addressUuid != null) ^ (address != null);
    }

    /** A one-off delivery address supplied at order time (not saved to the address book). */
    public record InlineAddress(
            @NotBlank String recipient,
            @NotBlank String phone,
            @NotBlank @Size(min = 2, max = 2) String country,
            @NotBlank String roadAddress,
            String detailAddress,
            @NotBlank String postalCode) {
    }
}
