package com.example.bookserver.cart;

import java.util.UUID;

/** The book is not in the current user's cart. */
public class CartItemNotFoundException extends RuntimeException {

    public CartItemNotFoundException(UUID bookUuid) {
        super("Book not in cart: " + bookUuid);
    }
}
