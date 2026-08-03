package com.example.bookserver.cart;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.book.BookService;
import com.example.bookserver.book.dto.BookRequest;
import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the whole cart flow under JWT security, against the real DB:
 * register -> login -> create a book -> add to cart -> list -> change qty -> remove.
 * Proves cart operations are scoped to the authenticated user end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
class CartControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BookService bookService;

    private static final String REGISTER_BODY = """
            {"userId":"jdoe","password":"secret","userName":"Jane Doe",
             "phone":"010-1234-5678","birthDate":"1990-05-20"}
            """;

    private static final String LOGIN_BODY = """
            {"userId":"jdoe","password":"secret"}
            """;

    /** Register + login, returning the access token. */
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
     * but this test is about the cart, not catalog authorization — so it creates the book
     * at the service layer rather than logging in as an admin.
     */
    private String createBook() {
        return bookService.create(new BookRequest("Clean Architecture", "desc",
                new BigDecimal("39.99"), LocalDate.of(2021, 1, 1), "Wikibooks", 10, List.of()))
                .toString();
    }

    // Full round-trip: add -> list -> change quantity -> remove, all with the access token.
    @Test
    void add_list_change_remove_roundTrip() throws Exception {
        String token = registerAndLogin();
        String book = createBook();

        // add 2
        mockMvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookUuid\":\"" + book + "\",\"quantity\":2}"))
                .andExpect(status().isCreated());

        // list -> one line, enriched with title/price and quantity 2
        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookUuid").value(book))
                .andExpect(jsonPath("$[0].bookTitle").value("Clean Architecture"))
                .andExpect(jsonPath("$[0].price").value(39.99))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[0].lineTotal").value(79.98));   // 39.99 * 2

        // change to 5
        mockMvc.perform(put("/api/cart/items/" + book).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].quantity").value(5));

        // remove -> empty cart
        mockMvc.perform(delete("/api/cart/items/" + book).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // The cart requires authentication.
    @Test
    void cart_returns401_withoutToken() throws Exception {
        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isUnauthorized());
    }
}
