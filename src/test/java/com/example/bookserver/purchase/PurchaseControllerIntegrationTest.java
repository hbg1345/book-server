package com.example.bookserver.purchase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.book.BookService;
import com.example.bookserver.book.dto.BookRequest;
import com.example.bookserver.payment.ChargeResult;
import com.example.bookserver.payment.PaymentGateway;
import com.example.bookserver.payment.PaymentMapper;
import com.example.bookserver.payment.PaymentStatus;
import com.example.bookserver.user.UserService;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end smoke test for the order flow under JWT security, against the real DB:
 * register → login → create a book → add to cart → place order → pay → cancel.
 * Proves stock is reserved and restored and that the state timeline is recorded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
class PurchaseControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BookService bookService;
    @Autowired
    private UserService userService;
    @Autowired
    private PaymentMapper paymentMapper;
    // Override the declining prod stub with a gateway that approves, so the pay flow can complete.
    @MockitoBean
    private PaymentGateway paymentGateway;

    @BeforeEach
    void approveCharges() {
        when(paymentGateway.confirm(any())).thenReturn(ChargeResult.paid("txn_it"));
    }

    private static final String REGISTER_BODY = """
            {"userId":"jdoe","password":"secret","userName":"Jane Doe",
             "phone":"010-1234-5678","birthDate":"1990-05-20"}
            """;

    private static final String LOGIN_BODY = """
            {"userId":"jdoe","password":"secret"}
            """;

    private static final String INLINE_ADDRESS_BODY = """
            {"address":{"recipient":"Jane Doe","phone":"010-1234-5678","country":"KR",
             "roadAddress":"123 Sejong-daero","detailAddress":"5F","postalCode":"06236"}}
            """;

    private String registerAndLogin() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
    }

    /**
     * Seed a book directly through the service. Catalog writes are admin-only over HTTP,
     * but this test is about the order flow, not catalog authorization — so it creates the
     * book at the service layer rather than logging in as an admin.
     */
    private String createBook() {
        return bookService.create(new BookRequest("Clean Architecture", "desc",
                new BigDecimal("39.99"), LocalDate.of(2021, 1, 1), "Wikibooks", 10, List.of()))
                .toString();
    }

    // register -> login -> create book -> add to cart -> place -> pay -> cancel, end to end.
    @Test
    void place_pay_cancel_roundTrip() throws Exception {
        String token = registerAndLogin();
        String book = createBook();

        mockMvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookUuid\":\"" + book + "\",\"quantity\":2}"))
                .andExpect(status().isCreated());

        // place the order -> PAYMENT_PENDING, stock reserved (10 -> 8), cart emptied
        MvcResult placed = mockMvc.perform(post("/api/orders").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(INLINE_ADDRESS_BODY))
                .andExpect(status().isCreated())
                .andReturn();
        String order = JsonPath.read(placed.getResponse().getContentAsString(), "$.purchaseUuid");

        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));   // cart drained into the order
        mockMvc.perform(get("/api/books/" + book))
                .andExpect(jsonPath("$.inventory").value(8));   // reserved

        // detail: header + snapshotted delivery address + one item (title/lineTotal) + a one-event timeline
        mockMvc.perform(get("/api/orders/" + order).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchaseState").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.price").value(79.98))
                .andExpect(jsonPath("$.deliveryAddress.recipient").value("Jane Doe"))
                .andExpect(jsonPath("$.deliveryAddress.postalCode").value("06236"))
                .andExpect(jsonPath("$.items[0].bookTitle").value("Clean Architecture"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal").value(79.98))
                .andExpect(jsonPath("$.history.length()").value(1));

        // pay -> charge succeeds (fake gateway), ORDERED, payment persisted, timeline grows
        mockMvc.perform(post("/api/orders/" + order + "/pay").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"TOSS\",\"paymentKey\":\"pk_rt\",\"amount\":79.98}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        assertThat(paymentMapper.findByPurchaseUuid(java.util.UUID.fromString(order)).getStatus())
                .isEqualTo(PaymentStatus.PAID);   // record persisted for reconciliation
        mockMvc.perform(get("/api/orders/" + order).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.purchaseState").value("ORDERED"))
                .andExpect(jsonPath("$.history.length()").value(2));

        // cancel -> CANCELLED, stock restored (8 -> 10)
        mockMvc.perform(post("/api/orders/" + order + "/cancel").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/orders/" + order).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.purchaseState").value("CANCELLED"))
                .andExpect(jsonPath("$.history.length()").value(3));
        mockMvc.perform(get("/api/books/" + book))
                .andExpect(jsonPath("$.inventory").value(10));   // stock given back
    }

    // order to a SAVED address snapshots it; editing that address afterwards leaves the order unchanged.
    @Test
    void order_withSavedAddress_snapshotSurvivesLaterEdit() throws Exception {
        String token = registerAndLogin();
        String book = createBook();

        MvcResult saved = mockMvc.perform(post("/api/addresses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"alias":"Home","recipient":"Grace Hopper","phone":"010-1111-2222","country":"KR",
                                 "roadAddress":"1 Original Road","detailAddress":"101","postalCode":"06236","defaultAddress":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String addressId = JsonPath.read(saved.getResponse().getContentAsString(), "$.addressUuid");

        mockMvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookUuid\":\"" + book + "\",\"quantity\":1}"))
                .andExpect(status().isCreated());

        // place the order picking the saved address
        MvcResult placed = mockMvc.perform(post("/api/orders").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressUuid\":\"" + addressId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String order = JsonPath.read(placed.getResponse().getContentAsString(), "$.purchaseUuid");

        // the order carries the snapshot
        mockMvc.perform(get("/api/orders/" + order).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.deliveryAddress.recipient").value("Grace Hopper"))
                .andExpect(jsonPath("$.deliveryAddress.roadAddress").value("1 Original Road"));

        // edit the saved address to entirely different values
        mockMvc.perform(put("/api/addresses/" + addressId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"alias":"Home","recipient":"Ada Lovelace","phone":"010-9999-9999","country":"KR",
                                 "roadAddress":"999 New Road","detailAddress":"202","postalCode":"04524","defaultAddress":true}
                                """))
                .andExpect(status().isOk());

        // the past order is unaffected — still the original snapshot
        mockMvc.perform(get("/api/orders/" + order).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.deliveryAddress.recipient").value("Grace Hopper"))
                .andExpect(jsonPath("$.deliveryAddress.roadAddress").value("1 Original Road"))
                .andExpect(jsonPath("$.deliveryAddress.postalCode").value("06236"));
    }

    /** Seed an ADMIN account and log in, returning its access token. */
    private String adminToken() throws Exception {
        userService.ensureAdminAccount("admin", "secret", "Admin",
                "010-0000-0000", LocalDate.of(2000, 1, 1));
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"admin","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
    }

    // full fulfillment lifecycle: buyer pays, admin prepares/ships/delivers, buyer confirms;
    // the buyer cannot drive admin transitions, and the tracking number is captured and kept.
    @Test
    void fulfillment_lifecycle_endToEnd() throws Exception {
        String buyer = registerAndLogin();
        String admin = adminToken();
        String book = createBook();

        mockMvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookUuid\":\"" + book + "\",\"quantity\":1}"))
                .andExpect(status().isCreated());
        MvcResult placed = mockMvc.perform(post("/api/orders").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content(INLINE_ADDRESS_BODY))
                .andExpect(status().isCreated())
                .andReturn();
        String order = JsonPath.read(placed.getResponse().getContentAsString(), "$.purchaseUuid");
        mockMvc.perform(post("/api/orders/" + order + "/pay").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"TOSS\",\"paymentKey\":\"pk_ff\",\"amount\":39.99}"))
                .andExpect(status().isOk());   // -> ORDERED

        // the buyer cannot drive fulfillment transitions
        mockMvc.perform(post("/api/orders/" + order + "/prepare").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isForbidden());

        // admin walks it through preparation -> shipping (with tracking) -> delivered
        mockMvc.perform(post("/api/orders/" + order + "/prepare").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/" + order + "/ship").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"trackingNumber\":\"1Z-TRACK-1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/" + order + "/deliver").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/" + order).header("Authorization", "Bearer " + buyer))
                .andExpect(jsonPath("$.purchaseState").value("DELIVERED"))
                .andExpect(jsonPath("$.trackingNumber").value("1Z-TRACK-1"));

        // buyer confirms receipt
        mockMvc.perform(post("/api/orders/" + order + "/confirm").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/orders/" + order).header("Authorization", "Bearer " + buyer))
                .andExpect(jsonPath("$.purchaseState").value("CONFIRMED"))
                .andExpect(jsonPath("$.trackingNumber").value("1Z-TRACK-1"));   // preserved through transitions

        // an illegal transition (delivering an already-confirmed order) -> 409
        mockMvc.perform(post("/api/orders/" + order + "/deliver").header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
    }

    // orders require authentication.
    @Test
    void orders_returns401_withoutToken() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());
    }
}
