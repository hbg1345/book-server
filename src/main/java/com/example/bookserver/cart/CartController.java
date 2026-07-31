package com.example.bookserver.cart;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.cart.dto.AddCartItemRequest;
import com.example.bookserver.cart.dto.CartItemResponse;
import com.example.bookserver.cart.dto.UpdateCartItemRequest;

import jakarta.validation.Valid;

/**
 * Shopping-cart endpoints for the authenticated user. The user's uuid is carried by
 * the JWT and injected via {@link AuthenticationPrincipal}; every operation is scoped
 * to that user, so a cart is never addressable by anyone else.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public void addItem(@AuthenticationPrincipal UUID userUuid,
                        @Valid @RequestBody AddCartItemRequest req) {
        cartService.addItem(userUuid, req.bookUuid(), req.quantity());
    }

    @GetMapping
    public List<CartItemResponse> myCart(@AuthenticationPrincipal UUID userUuid) {
        return cartService.listMyCart(userUuid).stream().map(CartItemResponse::from).toList();
    }

    @PutMapping("/items/{bookUuid}")
    public void changeQuantity(@AuthenticationPrincipal UUID userUuid,
                               @PathVariable UUID bookUuid,
                               @Valid @RequestBody UpdateCartItemRequest req) {
        cartService.changeQuantity(userUuid, bookUuid, req.quantity());
    }

    @DeleteMapping("/items/{bookUuid}")
    public void removeItem(@AuthenticationPrincipal UUID userUuid,
                           @PathVariable UUID bookUuid) {
        cartService.removeItem(userUuid, bookUuid);
    }
}
