package com.example.bookserver.book;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.UserService;
import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the whole stack: real controller -> real {@link BookService} -> real
 * mapper -> real PostgreSQL (Testcontainers). Deliberately minimal — it proves the layers
 * are wired together and the book + author (M:N) mapping actually assembles end-to-end.
 * The exhaustive per-case checks live in the fast {@code BookControllerWebMvcTest} slice.
 * Catalog writes are admin-only, so it drives them with a real ADMIN access token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
class BookControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthorMapper authorMapper;
    @Autowired
    private UserService userService;

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

    // create (with a linked author) -> get returns book + author -> delete -> 404.
    @Test
    void create_get_delete_roundTrip() throws Exception {
        String token = adminToken();

        Author author = new Author(Uuids.newId(), "Robert Martin");
        authorMapper.insert(author);

        String createBody = """
                {"bookTitle":"Clean Architecture","bookDescription":"desc",
                 "price":39.99,"publishDate":"2021-01-01","publisher":"Wikibooks","inventory":10,
                 "authorUuids":["%s"]}
                """.formatted(author.getAuthorUuid());

        MvcResult created = mockMvc.perform(post("/api/books")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookUuid").isNotEmpty())
                .andReturn();

        String bookUuid = JsonPath.read(created.getResponse().getContentAsString(), "$.bookUuid");

        mockMvc.perform(get("/api/books/{bookUuid}", bookUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookTitle").value("Clean Architecture"))
                .andExpect(jsonPath("$.price").value(39.99))
                .andExpect(jsonPath("$.authors[0].authorName").value("Robert Martin"));

        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].bookUuid").value(bookUuid));

        mockMvc.perform(delete("/api/books/{bookUuid}", bookUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/books/{bookUuid}", bookUuid))
                .andExpect(status().isNotFound());
    }

    /**
     * An edit and a stock adjustment, end to end, against the real database.
     *
     * <p>The edit sends no stock figure — the body has no field for one — and the book keeps
     * what it held. Stock then moves by a delta, and a write-off larger than the shelf is
     * refused rather than driving the count below zero.
     */
    @Test
    void editingABook_leavesStockToTheStockEndpoint() throws Exception {
        String token = adminToken();
        String bookUuid = createBook(token, "Clean Architecture");

        mockMvc.perform(put("/api/books/{bookUuid}", bookUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookTitle":"Clean Architecture, 2nd ed.","bookDescription":"desc",
                                 "price":39.99,"publishDate":"2021-01-01","publisher":"Wikibooks",
                                 "authorUuids":[]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/books/{bookUuid}", bookUuid))
                .andExpect(jsonPath("$.bookTitle").value("Clean Architecture, 2nd ed."))
                .andExpect(jsonPath("$.inventory").value(10));   // the edit said nothing about it

        mockMvc.perform(post("/api/books/{bookUuid}/stock", bookUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventory").value(30));

        mockMvc.perform(post("/api/books/{bookUuid}/stock", bookUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-31}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/books/{bookUuid}", bookUuid))
                .andExpect(jsonPath("$.inventory").value(30));   // the refusal changed nothing
    }

    /** Create a book through the API and return its uuid. */
    private String createBook(String token, String title) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/books")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookTitle":"%s","bookDescription":"desc",
                                 "price":39.99,"publishDate":"2021-01-01","publisher":"Wikibooks",
                                 "inventory":10,"authorUuids":[]}
                                """.formatted(title)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.bookUuid");
    }

    // ?title= filters against the real database, and the blank-query rejection survives the
    // trip through the Problem Details handler. The web slice mocks the service, so this is
    // the only place the query actually reaches PostgreSQL.
    @Test
    void search_filtersAgainstTheDatabase() throws Exception {
        String token = adminToken();
        String cleanCode = createBook(token, "Clean Code");
        createBook(token, "Refactoring");

        mockMvc.perform(get("/api/books").param("title", "clean"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].bookUuid").value(cleanCode));

        // no filter still lists everything, so search did not replace the plain read
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/books").param("title", "   "))
                .andExpect(status().isBadRequest());
    }
}
