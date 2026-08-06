package com.example.bookserver.purchase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.example.bookserver.address.AddressNotFoundException;
import com.example.bookserver.payment.Payment;
import com.example.bookserver.payment.OpenedPayment;
import com.example.bookserver.payment.PaymentIntentFailedException;
import com.example.bookserver.payment.PaymentStatus;
import com.example.bookserver.payment.RefundFailedException;
import com.example.bookserver.auth.JwtProvider;
import com.example.bookserver.auth.SecurityConfig;
import com.example.bookserver.common.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice test for the order endpoints under the real security filter chain.
 * Every route requires an authenticated principal and delegates to the service scoped
 * to that user's uuid.
 */
@WebMvcTest(PurchaseController.class)
@AutoConfigureRestDocs
@org.springframework.context.annotation.Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtProvider.class})
class PurchaseControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PurchaseService purchaseService;
    @MockitoBean
    private OrderExpiryScheduler orderExpiryScheduler;

    private static RequestPostProcessor asUser(UUID uuid) {
        return authentication(new UsernamePasswordAuthenticationToken(uuid, null, List.of()));
    }

    private static RequestPostProcessor asAdmin(UUID uuid) {
        return authentication(new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    // a valid order body shipping to a one-off inline address
    private static final String INLINE_ADDRESS_BODY = """
            {"address":{"recipient":"Jane Doe","phone":"010-1234-5678","country":"KR",
             "roadAddress":"123 Sejong-daero","detailAddress":"5F","postalCode":"06236"}}
            """;

    // place: 201 + new purchase uuid; the service is called with the authenticated user and the body.
    @Test
    void place_returns201AndPurchaseUuid() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        when(purchaseService.placeOrder(eq(user), any())).thenReturn(purchase);

        mockMvc.perform(post("/api/orders").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON).content(INLINE_ADDRESS_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purchaseUuid").value(purchase.toString()))
                .andDo(document("order-place",
                        requestFields(
                                fieldWithPath("addressUuid").type(JsonFieldType.STRING).optional().description("Saved address id to ship to; omit when supplying an inline address"),
                                fieldWithPath("address").type(JsonFieldType.OBJECT).optional().description("One-off delivery address; omit when using addressUuid"),
                                fieldWithPath("address.recipient").optional().description("Recipient name"),
                                fieldWithPath("address.phone").optional().description("Recipient phone"),
                                fieldWithPath("address.country").optional().description("ISO 3166-1 alpha-2 country code"),
                                fieldWithPath("address.roadAddress").optional().description("Road-name address line"),
                                fieldWithPath("address.detailAddress").optional().description("Unit/floor; optional"),
                                fieldWithPath("address.postalCode").optional().description("Postal code (format validated per country)")),
                        responseFields(
                                fieldWithPath("purchaseUuid").description("UUID of the newly placed order"))));

        verify(purchaseService).placeOrder(eq(user), any());
        verify(orderExpiryScheduler).scheduleExpiry(purchase);   // per-order expiry is scheduled
    }

    // place without authentication -> 401; service never touched.
    @Test
    void place_returns401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(INLINE_ADDRESS_BODY))
                .andExpect(status().isUnauthorized());
        verify(purchaseService, never()).placeOrder(any(), any());
    }

    // place with neither addressUuid nor an inline address -> 400 (validation), service untouched.
    // The cross-field error is reported under a real field key ("address"), not an internal
    // accessor name like "exactlyOneAddressSource".
    @Test
    void place_returns400_whenNoAddress() throws Exception {
        UUID user = UUID.randomUUID();

        mockMvc.perform(post("/api/orders").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.address").exists())
                .andExpect(jsonPath("$.errors.exactlyOneAddressSource").doesNotExist());
        verify(purchaseService, never()).placeOrder(any(), any());
    }

    // an inline field longer than its column limit -> 400 (validation), not a 500 DB overflow.
    @Test
    void place_returns400_whenFieldTooLong() throws Exception {
        UUID user = UUID.randomUUID();
        String longRoad = "a".repeat(256);   // road_address is VARCHAR(255)
        String body = "{\"address\":{\"recipient\":\"Jane\",\"phone\":\"010-1\",\"country\":\"KR\","
                + "\"roadAddress\":\"" + longRoad + "\",\"postalCode\":\"06236\"}}";

        mockMvc.perform(post("/api/orders").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['address.roadAddress']").exists());
        verify(purchaseService, never()).placeOrder(any(), any());
    }

    // place naming an address the caller does not own -> 404.
    @Test
    void place_returns404_whenAddressNotOwned() throws Exception {
        UUID user = UUID.randomUUID();
        UUID addressUuid = UUID.randomUUID();
        when(purchaseService.placeOrder(eq(user), any()))
                .thenThrow(new AddressNotFoundException(addressUuid));

        mockMvc.perform(post("/api/orders").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressUuid\":\"" + addressUuid + "\"}"))
                .andExpect(status().isNotFound());
    }

    // place from an empty cart -> 400.
    @Test
    void place_returns400_whenCartEmpty() throws Exception {
        UUID user = UUID.randomUUID();
        when(purchaseService.placeOrder(eq(user), any())).thenThrow(new EmptyCartException());

        mockMvc.perform(post("/api/orders").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON).content(INLINE_ADDRESS_BODY))
                .andExpect(status().isBadRequest());
    }

    // place with a book short on stock -> 409.
    @Test
    void place_returns409_whenStockInsufficient() throws Exception {
        UUID user = UUID.randomUUID();
        when(purchaseService.placeOrder(eq(user), any()))
                .thenThrow(new InsufficientInventoryException(UUID.randomUUID()));

        mockMvc.perform(post("/api/orders").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON).content(INLINE_ADDRESS_BODY))
                .andExpect(status().isConflict());
    }

    // list: 200 with the caller's orders (current state + total).
    @Test
    void myOrders_returnsList() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        when(purchaseService.listMyOrders(user)).thenReturn(List.of(
                new PurchaseCurrent(purchase, user, UUID.randomUUID(), PurchaseState.ORDERED,
                        new BigDecimal("79.98"), LocalDateTime.of(2026, 7, 31, 10, 0))));

        mockMvc.perform(get("/api/orders").with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].purchaseUuid").value(purchase.toString()))
                .andExpect(jsonPath("$[0].purchaseState").value("ORDERED"))
                .andExpect(jsonPath("$[0].price").value(79.98))
                .andDo(document("order-list",
                        responseFields(
                                fieldWithPath("[].purchaseUuid").description("Order UUID"),
                                fieldWithPath("[].purchaseState").description("Current order state"),
                                fieldWithPath("[].price").description("Order total"),
                                fieldWithPath("[].updatedAt").description("When the current state took effect"))));
    }

    // detail: 200 with header, items and the state timeline.
    @Test
    void detail_returnsOrder() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        UUID book = UUID.randomUUID();
        LocalDateTime at = LocalDateTime.of(2026, 7, 31, 10, 0);
        PurchaseCurrent current = new PurchaseCurrent(purchase, user, UUID.randomUUID(),
                PurchaseState.PAYMENT_PENDING, new BigDecimal("79.98"), at);
        current.setTrackingNumber("1Z999AA10123456784");
        OrderDetail detail = new OrderDetail(
                current,
                new OrderAddress(purchase, "Jane Doe", "010-1234-5678", "KR",
                        "123 Sejong-daero", "5F", "06236", null, at),
                List.of(new OrderBookItem(book, "Clean Architecture", 2, new BigDecimal("39.99"))),
                List.of(new PurchaseHistory(UUID.randomUUID(), purchase, user, PurchaseState.PAYMENT_PENDING,
                        new BigDecimal("79.98"), at)));
        when(purchaseService.getOrder(user, purchase)).thenReturn(detail);

        mockMvc.perform(get("/api/orders/" + purchase).with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchaseUuid").value(purchase.toString()))
                .andExpect(jsonPath("$.purchaseState").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.trackingNumber").value("1Z999AA10123456784"))
                .andExpect(jsonPath("$.deliveryAddress.recipient").value("Jane Doe"))
                .andExpect(jsonPath("$.deliveryAddress.postalCode").value("06236"))
                .andExpect(jsonPath("$.items[0].bookTitle").value("Clean Architecture"))
                .andExpect(jsonPath("$.items[0].lineTotal").value(79.98))
                .andExpect(jsonPath("$.history[0].purchaseState").value("PAYMENT_PENDING"))
                .andDo(document("order-detail",
                        responseFields(
                                fieldWithPath("purchaseUuid").description("Order UUID"),
                                fieldWithPath("purchaseState").description("Current order state"),
                                fieldWithPath("price").description("Order total"),
                                fieldWithPath("updatedAt").description("When the current state took effect"),
                                fieldWithPath("trackingNumber").type(JsonFieldType.STRING).optional().description("Shipment tracking number; null until the order ships"),
                                fieldWithPath("deliveryAddress.recipient").description("Recipient name (snapshot)"),
                                fieldWithPath("deliveryAddress.phone").description("Recipient phone (snapshot)"),
                                fieldWithPath("deliveryAddress.country").description("ISO 3166-1 alpha-2 country code"),
                                fieldWithPath("deliveryAddress.roadAddress").description("Road-name address line (snapshot)"),
                                fieldWithPath("deliveryAddress.detailAddress").description("Unit/floor (snapshot); may be null"),
                                fieldWithPath("deliveryAddress.postalCode").description("Postal code (snapshot)"),
                                fieldWithPath("items[].bookUuid").description("Book UUID"),
                                fieldWithPath("items[].bookTitle").description("Book title"),
                                fieldWithPath("items[].quantity").description("Quantity ordered"),
                                fieldWithPath("items[].price").description("Unit price snapshot"),
                                fieldWithPath("items[].lineTotal").description("price × quantity"),
                                fieldWithPath("history[].purchaseState").description("State at this event"),
                                fieldWithPath("history[].price").description("Order total at this event"),
                                fieldWithPath("history[].updatedAt").description("When this state took effect"))));
    }

    // detail of a missing / others' order -> 404.
    @Test
    void detail_returns404_whenNotFound() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        when(purchaseService.getOrder(user, purchase)).thenThrow(new OrderNotFoundException(purchase));

        mockMvc.perform(get("/api/orders/" + purchase).with(asUser(user)))
                .andExpect(status().isNotFound());
    }

    private static Payment payment(UUID purchase, PaymentStatus status) {
        return new Payment(UUID.randomUUID(), purchase, "STRIPE", "pi_1",
                new BigDecimal("79.98"), status, BigDecimal.ZERO, "order-" + purchase, null, null);
    }

    // payment-intent: 200 with the client secret; delegates to the service. No request body —
    // the amount is the server's own order total.
    @Test
    void openPaymentIntent_returns200AndClientSecret() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        when(purchaseService.openPaymentIntent(user, purchase))
                .thenReturn(new OpenedPayment(payment(purchase, PaymentStatus.PENDING), "cs_test_123"));

        mockMvc.perform(post("/api/orders/" + purchase + "/payment-intent").with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").value("cs_test_123"))
                .andExpect(jsonPath("$.amount").value(79.98))
                .andExpect(jsonPath("$.paymentUuid").exists())
                .andDo(document("order-payment-intent",
                        responseFields(
                                fieldWithPath("paymentUuid").description("Payment record id"),
                                fieldWithPath("clientSecret").description(
                                        "Provider client secret the frontend confirms the card with"),
                                fieldWithPath("amount").description(
                                        "Server-computed order total that will be charged"))));

        verify(purchaseService).openPaymentIntent(user, purchase);
    }

    // opening an intent on an order that is not awaiting payment -> 409.
    @Test
    void openPaymentIntent_returns409_whenNotPending() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        doThrow(new IllegalOrderStateException("not pending"))
                .when(purchaseService).openPaymentIntent(user, purchase);

        mockMvc.perform(post("/api/orders/" + purchase + "/payment-intent").with(asUser(user)))
                .andExpect(status().isConflict());
    }

    // the provider refusing to open an intent -> 502 (our side failed, not the caller's).
    @Test
    void openPaymentIntent_returns502_whenProviderFails() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        doThrow(new PaymentIntentFailedException(purchase, "provider_unavailable"))
                .when(purchaseService).openPaymentIntent(user, purchase);

        mockMvc.perform(post("/api/orders/" + purchase + "/payment-intent").with(asUser(user)))
                .andExpect(status().isBadGateway());
    }

    // an intent on a missing / others' order -> 404.
    @Test
    void openPaymentIntent_returns404_whenNotFound() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        doThrow(new OrderNotFoundException(purchase))
                .when(purchaseService).openPaymentIntent(user, purchase);

        mockMvc.perform(post("/api/orders/" + purchase + "/payment-intent").with(asUser(user)))
                .andExpect(status().isNotFound());
    }
    // cancel: 200; delegates to the service.
    @Test
    void cancel_delegatesToService() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();

        mockMvc.perform(post("/api/orders/" + purchase + "/cancel").with(asUser(user)))
                .andExpect(status().isOk())
                .andDo(document("order-cancel"));

        verify(purchaseService).cancel(user, purchase);
    }

    // cancel an order past the cancellable window -> 409.
    @Test
    void cancel_returns409_whenNotCancellable() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        doThrow(new IllegalOrderStateException("too late"))
                .when(purchaseService).cancel(eq(user), eq(purchase));

        mockMvc.perform(post("/api/orders/" + purchase + "/cancel").with(asUser(user)))
                .andExpect(status().isConflict());
    }

    // --- fulfillment lifecycle (#26) ---

    // prepare: 200, delegates to the service; admin-only.
    @Test
    void prepare_delegatesToService_forAdmin() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();

        mockMvc.perform(post("/api/orders/" + purchase + "/prepare").with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andDo(document("order-prepare"));

        verify(purchaseService).prepare(purchase);
    }

    // a non-admin cannot drive fulfillment transitions -> 403; service untouched.
    @Test
    void prepare_returns403_forNonAdmin() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();

        mockMvc.perform(post("/api/orders/" + purchase + "/prepare").with(asUser(user)))
                .andExpect(status().isForbidden());
        verify(purchaseService, never()).prepare(any());
    }

    // ship: 200, captures the tracking number from the body; admin-only.
    @Test
    void ship_capturesTrackingNumber_forAdmin() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();

        mockMvc.perform(post("/api/orders/" + purchase + "/ship").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingNumber\":\"1Z999AA10123456784\"}"))
                .andExpect(status().isOk())
                .andDo(document("order-ship",
                        requestFields(
                                fieldWithPath("trackingNumber").description("Carrier tracking number"))));

        verify(purchaseService).ship(purchase, "1Z999AA10123456784");
    }

    // ship without a tracking number -> 400; service untouched.
    @Test
    void ship_returns400_whenTrackingMissing() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();

        mockMvc.perform(post("/api/orders/" + purchase + "/ship").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(purchaseService, never()).ship(any(), any());
    }

    // deliver: 200, delegates to the service; admin-only.
    @Test
    void deliver_delegatesToService_forAdmin() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();

        mockMvc.perform(post("/api/orders/" + purchase + "/deliver").with(asAdmin(admin)))
                .andExpect(status().isOk())
                .andDo(document("order-deliver"));

        verify(purchaseService).deliver(purchase);
    }

    // confirm: 200, the buyer's action, scoped to the caller.
    @Test
    void confirm_delegatesToService_forBuyer() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();

        mockMvc.perform(post("/api/orders/" + purchase + "/confirm").with(asUser(user)))
                .andExpect(status().isOk())
                .andDo(document("order-confirm"));

        verify(purchaseService).confirm(user, purchase);
    }

    // an illegal transition -> 409 (like pay/cancel).
    @Test
    void prepare_returns409_whenIllegalTransition() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        doThrow(new IllegalOrderStateException("not ordered")).when(purchaseService).prepare(purchase);

        mockMvc.perform(post("/api/orders/" + purchase + "/prepare").with(asAdmin(admin)))
                .andExpect(status().isConflict());
    }

    // --- refund / return (#25 PR-2) ---

    // return: 200, the buyer's action, delegates to the service scoped to the caller.
    @Test
    void return_delegatesToService_forBuyer() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();

        mockMvc.perform(post("/api/orders/" + purchase + "/return").with(asUser(user)))
                .andExpect(status().isOk())
                .andDo(document("order-return"));

        verify(purchaseService).returnOrder(user, purchase);
    }

    // returning an order that has not been delivered -> 409.
    @Test
    void return_returns409_whenNotReturnable() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        doThrow(new IllegalOrderStateException("not delivered")).when(purchaseService).returnOrder(user, purchase);

        mockMvc.perform(post("/api/orders/" + purchase + "/return").with(asUser(user)))
                .andExpect(status().isConflict());
    }

    // a refund the gateway could not complete -> 502.
    @Test
    void return_returns502_whenRefundFails() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        doThrow(new RefundFailedException(purchase)).when(purchaseService).returnOrder(user, purchase);

        mockMvc.perform(post("/api/orders/" + purchase + "/return").with(asUser(user)))
                .andExpect(status().isBadGateway());
    }

    // cancelling a paid order whose refund fails -> 502.
    @Test
    void cancel_returns502_whenRefundFails() throws Exception {
        UUID user = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        doThrow(new RefundFailedException(purchase)).when(purchaseService).cancel(user, purchase);

        mockMvc.perform(post("/api/orders/" + purchase + "/cancel").with(asUser(user)))
                .andExpect(status().isBadGateway());
    }
}
