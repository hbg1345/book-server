package com.example.bookserver.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for replacing a saved address (PUT /api/addresses/{addressUuid}). Same shape as
 * {@link CreateAddressRequest}: a full replacement of the mutable fields.
 */
public record UpdateAddressRequest(
        @NotBlank String alias,
        @NotBlank String recipient,
        @NotBlank String phone,
        @NotBlank @Size(min = 2, max = 2) String country,
        @NotBlank String roadAddress,
        String detailAddress,
        @NotBlank String postalCode,
        boolean defaultAddress) {
}
