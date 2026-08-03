package com.example.bookserver.address;

import java.util.UUID;

/**
 * The address does not exist, or does not belong to the requesting user. Both cases are
 * reported as this one exception (mapped to 404) so the API never reveals whether an
 * address id exists under a different owner.
 */
public class AddressNotFoundException extends RuntimeException {

    public AddressNotFoundException(UUID addressUuid) {
        super("Address not found: " + addressUuid);
    }
}
