package com.example.bookserver.address.dto;

import java.util.UUID;

/** Response for a successful create: the newly generated address_uuid. */
public record CreateAddressResponse(UUID addressUuid) {
}
