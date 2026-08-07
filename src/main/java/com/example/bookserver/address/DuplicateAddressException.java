package com.example.bookserver.address;

/**
 * The address is already in the user's book, so saving it again would duplicate it — the
 * shape a double-clicked form or a retried request takes. Backed by
 * {@code uq_address_no_duplicate_per_user}, so it is raised by the write itself rather than
 * by a check the second caller could race past. Mapped to 409.
 */
public class DuplicateAddressException extends RuntimeException {

    public DuplicateAddressException() {
        super("This address is already saved in your address book");
    }
}
