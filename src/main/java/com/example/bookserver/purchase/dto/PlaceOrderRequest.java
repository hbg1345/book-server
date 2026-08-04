package com.example.bookserver.purchase.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for placing an order (POST /api/orders). The order ships to EITHER a saved address
 * picked from the book ({@code addressUuid}) OR a one-off {@code address} supplied inline —
 * exactly one of the two ({@link ExactlyOneOrderAddress}). Whichever is used, its values are
 * snapshotted onto the order, so later edits to a saved address never change a past order.
 * The inline address mirrors the address-book shape (minus alias/default); field lengths match
 * the DB columns so an over-long value is a 400, not a DB overflow, and postal_code format is
 * validated per country in the service.
 */
@ExactlyOneOrderAddress
public record PlaceOrderRequest(
        UUID addressUuid,
        @Valid InlineAddress address) {

    /** A one-off delivery address supplied at order time (not saved to the address book). */
    public record InlineAddress(
            @NotBlank @Size(max = 100) String recipient,
            @NotBlank @Size(max = 20) String phone,
            @NotBlank @Size(min = 2, max = 2) String country,
            @NotBlank @Size(max = 255) String roadAddress,
            @Size(max = 255) String detailAddress,
            @NotBlank @Size(max = 20) String postalCode) {
    }
}
