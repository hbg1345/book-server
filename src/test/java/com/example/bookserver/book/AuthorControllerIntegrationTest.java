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
import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the whole stack: real controller -> real {@link AuthorService} -> real
 * mapper -> real PostgreSQL (Testcontainers). Proves author creation and name search
 * (with the author's books) work end-to-end, and that a created author can be linked
 * to a book and then surfaced by the search.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql("/schema.sql")
class AuthorControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // create author -> create a book linking that author -> search by name returns
    // the author together with the linked book's title.
    @Test
    void create_thenSearch_surfacesAuthorWithBooks() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/authors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"authorName":"Robert Martin"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorUuid").isNotEmpty())
                .andReturn();

        String authorUuid = JsonPath.read(created.getResponse().getContentAsString(), "$.authorUuid");

        mockMvc.perform(post("/api/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookTitle":"Clean Architecture","bookDescription":"desc",
                                 "price":39.99,"publishDate":"2021-01-01","publisher":"Wikibooks","inventory":10,
                                 "authorUuids":["%s"]}
                                """.formatted(authorUuid)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/authors").param("name", "Robert Martin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authorUuid").value(authorUuid))
                .andExpect(jsonPath("$[0].books[0]").value("Clean Architecture"));
    }
}
