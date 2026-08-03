package com.example.bookserver.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for saving a new address to the book (POST /api/addresses). Country is an
 * ISO 3166-1 alpha-2 code; postal_code format is validated per country in the service,
 * so only presence is enforced here. detailAddress is optional.
 */
public record CreateAddressRequest(
        @NotBlank String alias,
        @NotBlank String recipient,
        @NotBlank String phone,
        @NotBlank @Size(min = 2, max = 2) String country,
        @NotBlank String roadAddress,
        String detailAddress,
        @NotBlank String postalCode,
        boolean defaultAddress) {
}
