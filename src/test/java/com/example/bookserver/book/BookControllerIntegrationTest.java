package com.example.bookserver.book;

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
import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the whole stack: real controller -> real {@link BookService} -> real
 * mapper -> real PostgreSQL (Testcontainers). Deliberately minimal — it proves the layers
 * are wired together and the book + author (M:N) mapping actually assembles end-to-end.
 * The exhaustive per-case checks live in the fast {@code BookControllerWebMvcTest} slice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql("/schema.sql")
class BookControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthorMapper authorMapper;

    // create (with a linked author) -> get returns book + author -> delete -> 404.
    @Test
    void create_get_delete_roundTrip() throws Exception {
        Author author = new Author(Uuids.newId(), "Robert Martin");
        authorMapper.insert(author);

        String createBody = """
                {"bookTitle":"Clean Architecture","bookDescription":"desc",
                 "price":39.99,"publishDate":"2021-01-01","publisher":"Wikibooks","inventory":10,
                 "authorUuids":["%s"]}
                """.formatted(author.getAuthorUuid());

        MvcResult created = mockMvc.perform(post("/api/books")
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
                .andExpect(jsonPath("$[0].bookUuid").value(bookUuid));

        mockMvc.perform(delete("/api/books/{bookUuid}", bookUuid))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/books/{bookUuid}", bookUuid))
                .andExpect(status().isNotFound());
    }
}
