package com.example.bookserver.cart;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.bookserver.book.BookMapper;
import com.example.bookserver.book.BookNotFoundException;

/**
 * Shopping-cart operations, always scoped to the authenticated user's uuid.
 * A cart row is keyed by (user, book); adding a book already in the cart bumps
 * its quantity rather than creating a duplicate.
 */
@Service
public class CartService {

    private final CartItemMapper cartItemMapper;
    private final BookMapper bookMapper;

    public CartService(CartItemMapper cartItemMapper, BookMapper bookMapper) {
        this.cartItemMapper = cartItemMapper;
        this.bookMapper = bookMapper;
    }

    /** Add a book to the cart; if it is already there, increase the quantity. */
    @Transactional
    public void addItem(UUID userUuid, UUID bookUuid, int quantity) {
        requireBook(bookUuid);
        CartItem existing = cartItemMapper.findByUserAndBook(userUuid, bookUuid);
        if (existing == null) {
            cartItemMapper.insert(new CartItem(userUuid, bookUuid, quantity, null));
        } else {
            cartItemMapper.updateQuantity(userUuid, bookUuid, existing.getQuantity() + quantity);
        }
    }

    /** Every item in the user's cart, each joined with its book title and price. */
    public List<CartItemView> listMyCart(UUID userUuid) {
        return cartItemMapper.findByUserWithBook(userUuid);
    }

    /** Set the quantity of a book already in the cart. */
    @Transactional
    public void changeQuantity(UUID userUuid, UUID bookUuid, int quantity) {
        requireCartItem(userUuid, bookUuid);
        cartItemMapper.updateQuantity(userUuid, bookUuid, quantity);
    }

    /** Remove a book from the cart (idempotent). */
    public void removeItem(UUID userUuid, UUID bookUuid) {
        cartItemMapper.delete(userUuid, bookUuid);
    }

    private void requireBook(UUID bookUuid) {
        if (bookMapper.findById(bookUuid) == null) {
            throw new BookNotFoundException(bookUuid);
        }
    }

    private void requireCartItem(UUID userUuid, UUID bookUuid) {
        if (cartItemMapper.findByUserAndBook(userUuid, bookUuid) == null) {
            throw new CartItemNotFoundException(bookUuid);
        }
    }
}
