package com.example.bookserver.cart;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.example.bookserver.auth.JwtProvider;
import com.example.bookserver.auth.SecurityConfig;
import com.example.bookserver.common.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice test for the cart endpoints under the real security filter chain.
 * Every route requires an authenticated principal (the user's uuid, as the JWT filter
 * sets it) and delegates to the service scoped to that uuid.
 */
@WebMvcTest(CartController.class)
@AutoConfigureRestDocs
@org.springframework.context.annotation.Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtProvider.class})
class CartControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    /** Authenticate as the given user uuid (what the JWT filter does). */
    private static RequestPostProcessor asUser(UUID uuid) {
        return authentication(new UsernamePasswordAuthenticationToken(uuid, null, List.of()));
    }

    // add item: 201; the service is called with the authenticated user + parsed body.
    @Test
    void addItem_returns201_andDelegatesToService() throws Exception {
        UUID user = UUID.randomUUID();
        UUID book = UUID.randomUUID();

        mockMvc.perform(post("/api/cart/items").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookUuid\":\"" + book + "\",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andDo(document("cart-add-item",
                        requestFields(
                                fieldWithPath("bookUuid").description("UUID of the book to add"),
                                fieldWithPath("quantity").description("How many to add (>= 1)"))));

        verify(cartService).addItem(user, book, 2);
    }

    // add without authentication -> 401; service never touched.
    @Test
    void addItem_returns401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookUuid\":\"" + UUID.randomUUID() + "\",\"quantity\":2}"))
                .andExpect(status().isUnauthorized());

        verify(cartService, never()).addItem(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // add with an invalid body (quantity < 1) -> 400; service never reached.
    @Test
    void addItem_returns400_whenQuantityInvalid() throws Exception {
        UUID user = UUID.randomUUID();

        mockMvc.perform(post("/api/cart/items").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookUuid\":\"" + UUID.randomUUID() + "\",\"quantity\":0}"))
                .andExpect(status().isBadRequest());

        verify(cartService, never()).addItem(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // add for a book that does not exist -> 404 (mapped from the domain exception).
    @Test
    void addItem_returns404_whenBookMissing() throws Exception {
        UUID user = UUID.randomUUID();
        UUID book = UUID.randomUUID();
        doThrow(new com.example.bookserver.book.BookNotFoundException(book))
                .when(cartService).addItem(eq(user), eq(book), eq(1));

        mockMvc.perform(post("/api/cart/items").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookUuid\":\"" + book + "\",\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }

    // list: 200 with the caller's items, enriched with title, price and line subtotal.
    @Test
    void myCart_returnsItems() throws Exception {
        UUID user = UUID.randomUUID();
        UUID book = UUID.randomUUID();
        when(cartService.listMyCart(user)).thenReturn(List.of(
                new CartItemView(book, "Clean Architecture", new BigDecimal("39.99"), 3,
                        LocalDateTime.of(2026, 7, 31, 10, 0))));

        mockMvc.perform(get("/api/cart").with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookUuid").value(book.toString()))
                .andExpect(jsonPath("$[0].bookTitle").value("Clean Architecture"))
                .andExpect(jsonPath("$[0].price").value(39.99))
                .andExpect(jsonPath("$[0].quantity").value(3))
                .andExpect(jsonPath("$[0].lineTotal").value(119.97))   // 39.99 * 3
                .andDo(document("cart-list",
                        responseFields(
                                fieldWithPath("[].bookUuid").description("UUID of the book"),
                                fieldWithPath("[].bookTitle").description("Book title"),
                                fieldWithPath("[].price").description("Unit price of the book"),
                                fieldWithPath("[].quantity").description("Quantity in the cart"),
                                fieldWithPath("[].lineTotal").description("price × quantity for this line"),
                                fieldWithPath("[].createdAt").description("When the book was added"))));
    }

    // change quantity: 200; delegates with the authenticated user.
    @Test
    void changeQuantity_delegatesToService() throws Exception {
        UUID user = UUID.randomUUID();
        UUID book = UUID.randomUUID();

        mockMvc.perform(put("/api/cart/items/" + book).with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andDo(document("cart-change-quantity",
                        requestFields(
                                fieldWithPath("quantity").description("New quantity (>= 1)"))));

        verify(cartService).changeQuantity(user, book, 5);
    }

    // change quantity on a book not in the cart -> 404.
    @Test
    void changeQuantity_returns404_whenNotInCart() throws Exception {
        UUID user = UUID.randomUUID();
        UUID book = UUID.randomUUID();
        doThrow(new CartItemNotFoundException(book))
                .when(cartService).changeQuantity(eq(user), eq(book), eq(5));

        mockMvc.perform(put("/api/cart/items/" + book).with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isNotFound());
    }

    // remove: 200; delegates with the authenticated user.
    @Test
    void removeItem_delegatesToService() throws Exception {
        UUID user = UUID.randomUUID();
        UUID book = UUID.randomUUID();

        mockMvc.perform(delete("/api/cart/items/" + book).with(asUser(user)))
                .andExpect(status().isOk())
                .andDo(document("cart-remove-item"));

        verify(cartService).removeItem(user, book);
    }
}
