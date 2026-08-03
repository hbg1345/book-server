package com.example.bookserver.book;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.bookserver.auth.JwtProvider;
import com.example.bookserver.auth.SecurityConfig;
import com.example.bookserver.common.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice test: only the web layer is loaded and {@link AuthorService} is a mock.
 * Verifies HTTP wiring (routing, JSON (de)serialization, status codes, validation) — the
 * DB behaviour is already covered by {@link AuthorServiceTest}.
 */
@WebMvcTest(AuthorController.class)
@AutoConfigureRestDocs
@org.springframework.context.annotation.Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtProvider.class})
class AuthorControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthorService authorService;

    // create: 201 + generated uuid; service receives the name
    @Test
    void create_returns201AndUuid() throws Exception {
        UUID uuid = UUID.randomUUID();
        when(authorService.create("Robert Martin")).thenReturn(uuid);

        mockMvc.perform(post("/api/authors")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"authorName":"Robert Martin"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorUuid").value(uuid.toString()))
                .andDo(document("author-create",
                        requestFields(
                                fieldWithPath("authorName").description("Author's name")),
                        responseFields(
                                fieldWithPath("authorUuid").description("UUID of the newly created author"))));

        verify(authorService).create("Robert Martin");
    }

    // blank name -> 400 problem+json with a per-field error; service not reached
    @Test
    void create_returns400_whenNameBlank() throws Exception {
        mockMvc.perform(post("/api/authors")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"authorName":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors.authorName").exists());

        verify(authorService, never()).create(any());
    }

    // search: 200 with each author and their book titles
    @Test
    void search_returnsAuthorsWithBooks() throws Exception {
        AuthorSearchResult hit = new AuthorSearchResult(
                new Author(UUID.randomUUID(), "Kim"), List.of("Refactoring", "Clean Code"));
        when(authorService.searchByName("Kim")).thenReturn(List.of(hit));

        mockMvc.perform(get("/api/authors").param("name", "Kim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authorName").value("Kim"))
                .andExpect(jsonPath("$[0].books[0]").value("Refactoring"))
                .andDo(document("author-search",
                        queryParameters(
                                parameterWithName("name").description("Exact author name to search for")),
                        responseFields(
                                fieldWithPath("[].authorUuid").description("Author UUID"),
                                fieldWithPath("[].authorName").description("Author name"),
                                fieldWithPath("[].books").description("Titles of books this author wrote"))));
    }

    // missing required 'name' query param -> 400
    @Test
    void search_returns400_whenNameMissing() throws Exception {
        mockMvc.perform(get("/api/authors"))
                .andExpect(status().isBadRequest());

        verify(authorService, never()).searchByName(any());
    }

    // writes are admin-only: an anonymous create is rejected with 401
    @Test
    void create_returns401_whenAnonymous() throws Exception {
        mockMvc.perform(post("/api/authors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"authorName":"Robert Martin"}
                                """))
                .andExpect(status().isUnauthorized());

        verify(authorService, never()).create(any());
    }

    // writes are admin-only: an authenticated non-admin create is rejected with 403
    @Test
    void create_returns403_whenNotAdmin() throws Exception {
        mockMvc.perform(post("/api/authors")
                        .with(user("bob").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"authorName":"Robert Martin"}
                                """))
                .andExpect(status().isForbidden());

        verify(authorService, never()).create(any());
    }
}
