package com.example.bookserver.book;

import com.example.bookserver.Isbns;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import com.example.bookserver.book.dto.BookRequest;
import com.example.bookserver.book.dto.UpdateBookRequest;
import com.example.bookserver.common.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.payload.JsonFieldType.ARRAY;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice test: only the web layer is loaded and {@link BookService} is a mock.
 * Verifies HTTP wiring (routing, JSON (de)serialization, status codes, validation) — the
 * DB behaviour is already covered by {@link BookServiceTest}, so it is not re-tested here.
 */
@WebMvcTest(BookController.class)
@AutoConfigureRestDocs
@org.springframework.context.annotation.Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtProvider.class})
class BookControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookService bookService;

    private static final String CREATE_BODY = """
            {"isbn":"9780134494166",
             "bookTitle":"Clean Architecture","bookDescription":"A book about software architecture",
             "price":39.99,"publishDate":"2021-01-01","publisher":"Wikibooks","inventory":10,
             "authorUuids":[]}
            """;

    // no inventory: an edit says nothing about stock, which moves through /stock instead
    private static final String UPDATE_BODY = """
            {"bookTitle":"Clean Architecture","bookDescription":"A book about software architecture",
             "price":39.99,"publishDate":"2021-01-01","publisher":"Wikibooks",
             "authorUuids":[]}
            """;

    // create: 201 + generated uuid; service receives the parsed request
    @Test
    void create_returns201AndUuid() throws Exception {
        UUID uuid = UUID.randomUUID();
        when(bookService.create(any(BookRequest.class))).thenReturn(uuid);

        mockMvc.perform(post("/api/books")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookUuid").value(uuid.toString()))
                .andDo(document("book-create",
                        requestFields(
                                fieldWithPath("isbn").description(
                                        "ISBN-13: thirteen digits, 978/979 prefix, valid check "
                                                + "digit. Unique across the catalogue, so "
                                                + "resubmitting a book is a 409 rather than a "
                                                + "second copy of it"),
                                fieldWithPath("bookTitle").description("Book title"),
                                fieldWithPath("bookDescription").description("Description (optional)"),
                                fieldWithPath("price").description("Price (> 0, up to 2 decimals)"),
                                fieldWithPath("publishDate").description("Publish date (yyyy-MM-dd)"),
                                fieldWithPath("publisher").description("Publisher"),
                                fieldWithPath("inventory").description("Stock on hand (>= 0)"),
                                fieldWithPath("authorUuids").type(ARRAY)
                                        .description("UUIDs of existing authors to link (optional)")),
                        responseFields(
                                fieldWithPath("bookUuid").description("UUID of the newly created book"))));

        verify(bookService).create(any(BookRequest.class));
    }

    // invalid body (blank title, zero price) -> 400 problem+json with a per-field
    // "errors" map, and the service is never reached
    @Test
    void create_returns400WithFieldErrors_whenInvalid() throws Exception {
        mockMvc.perform(post("/api/books")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookTitle":"","bookDescription":"x","price":0,
                                 "publishDate":"2021-01-01","publisher":"Wikibooks","inventory":10}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors.bookTitle").exists())
                .andExpect(jsonPath("$.errors.price").exists());

        verify(bookService, never()).create(any());
    }

    // a malformed ISBN never reaches the service: the check digit is validated at the edge
    @Test
    void create_returns400_whenTheIsbnIsMalformed() throws Exception {
        mockMvc.perform(post("/api/books")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("9780134494166", "9780134494167")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.isbn").exists());

        verify(bookService, never()).create(any());
    }

    // a resubmitted book -> 409, the natural key doing what a fresh uuid per call cannot
    @Test
    void create_returns409_whenTheIsbnAlreadyExists() throws Exception {
        when(bookService.create(any(BookRequest.class)))
                .thenThrow(new DuplicateIsbnException("9780134494166"));

        mockMvc.perform(post("/api/books")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // get: 200 with the book and its authors
    @Test
    void get_returnsBookWithAuthors() throws Exception {
        UUID bookUuid = UUID.randomUUID();
        Author author = new Author(UUID.randomUUID(), "Robert Martin");
        Book book = new Book(bookUuid, Isbns.next(), "Clean Architecture", "desc",
                new BigDecimal("39.99"), LocalDate.of(2021, 1, 1), "Wikibooks", 10,
                List.of(author));
        when(bookService.get(bookUuid)).thenReturn(book);

        mockMvc.perform(get("/api/books/{bookUuid}", bookUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookTitle").value("Clean Architecture"))
                .andExpect(jsonPath("$.authors[0].authorName").value("Robert Martin"))
                .andDo(document("book-get",
                        responseFields(
                                fieldWithPath("bookUuid").description("Book UUID"),
                                fieldWithPath("isbn").description("ISBN-13"),
                                fieldWithPath("bookTitle").description("Book title"),
                                fieldWithPath("bookDescription").description("Description"),
                                fieldWithPath("price").description("Price"),
                                fieldWithPath("publishDate").description("Publish date"),
                                fieldWithPath("publisher").description("Publisher"),
                                fieldWithPath("inventory").description("Stock on hand"),
                                fieldWithPath("authors").description("Authors of the book"),
                                fieldWithPath("authors[].authorUuid").description("Author UUID"),
                                fieldWithPath("authors[].authorName").description("Author name"))));
    }

    // get on a missing id -> 404
    @Test
    void get_returns404_whenAbsent() throws Exception {
        UUID bookUuid = UUID.randomUUID();
        when(bookService.get(bookUuid)).thenThrow(new BookNotFoundException(bookUuid));

        mockMvc.perform(get("/api/books/{bookUuid}", bookUuid))
                .andExpect(status().isNotFound());
    }

    // The collection endpoint is search-only: ?title= states what the user is looking for.
    @Test
    void search_returnsMatchingBooks() throws Exception {
        Book book = new Book(UUID.randomUUID(), Isbns.next(), "Clean Architecture", "desc",
                new BigDecimal("39.99"), LocalDate.of(2021, 1, 1), "Wikibooks", 10, null);
        when(bookService.search("clean")).thenReturn(List.of(book));

        mockMvc.perform(get("/api/books").param("title", "clean"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].bookTitle").value("Clean Architecture"))
                .andDo(document("book-search",
                        queryParameters(
                                parameterWithName("title").description(
                                        "Required substring of the book title to search for, "
                                                + "case-insensitive."))));
    }

    // a blank title is a search with nothing to search for -> 400, not the whole catalogue
    @Test
    void search_returns400_whenTitleBlank() throws Exception {
        when(bookService.search("  ")).thenThrow(new BlankSearchQueryException());

        mockMvc.perform(get("/api/books").param("title", "  "))
                .andExpect(status().isBadRequest());

    }

    // reads stay public: search needs no token
    @Test
    void search_isPublic_whenAnonymous() throws Exception {
        when(bookService.search("clean")).thenReturn(List.of());

        mockMvc.perform(get("/api/books").param("title", "clean"))
                .andExpect(status().isOk());
    }

    // update: delegates to the service with the path uuid and parsed body
    @Test
    void update_delegatesToService() throws Exception {
        UUID bookUuid = UUID.randomUUID();

        mockMvc.perform(put("/api/books/{bookUuid}", bookUuid)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY))
                .andExpect(status().isOk());

        verify(bookService).update(eq(bookUuid), any(UpdateBookRequest.class));
    }

    // update on a missing id -> 404
    @Test
    void update_returns404_whenAbsent() throws Exception {
        UUID bookUuid = UUID.randomUUID();
        doThrow(new BookNotFoundException(bookUuid))
                .when(bookService).update(eq(bookUuid), any(UpdateBookRequest.class));

        mockMvc.perform(put("/api/books/{bookUuid}", bookUuid)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY))
                .andExpect(status().isNotFound());
    }

    // stock: the delta reaches the service and the new total comes back
    @Test
    void adjustStock_returnsNewTotal() throws Exception {
        UUID bookUuid = UUID.randomUUID();
        when(bookService.adjustStock(bookUuid, 20)).thenReturn(30);

        mockMvc.perform(post("/api/books/{bookUuid}/stock", bookUuid)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventory").value(30))
                .andDo(document("book-stock-adjust",
                        requestFields(
                                fieldWithPath("delta").description(
                                        "Copies to add (positive) or remove (negative). Not "
                                                + "idempotent: sending the same body twice moves "
                                                + "the stock twice")),
                        responseFields(
                                fieldWithPath("inventory").description(
                                        "Stock on hand after the adjustment"))));
    }

    // writing off more than the shop holds -> 409, not a negative stock level
    @Test
    void adjustStock_returns409_whenItWouldGoNegative() throws Exception {
        UUID bookUuid = UUID.randomUUID();
        when(bookService.adjustStock(bookUuid, -11))
                .thenThrow(new InsufficientStockException(bookUuid, -11));

        mockMvc.perform(post("/api/books/{bookUuid}/stock", bookUuid)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-11}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // moving stock is a catalogue write, so it is ADMIN-only like the rest of them
    @Test
    void adjustStock_returns403_whenNotAdmin() throws Exception {
        mockMvc.perform(post("/api/books/{bookUuid}/stock", UUID.randomUUID())
                        .with(user("shopper").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":20}"))
                .andExpect(status().isForbidden());

        verify(bookService, never()).adjustStock(any(), anyInt());
    }

    // delete: delegates to the service
    @Test
    void delete_delegatesToService() throws Exception {
        UUID bookUuid = UUID.randomUUID();

        mockMvc.perform(delete("/api/books/{bookUuid}", bookUuid)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(bookService).delete(bookUuid);
    }

    // writes are admin-only: an anonymous create is rejected with 401 and never reaches the service
    @Test
    void create_returns401_whenAnonymous() throws Exception {
        mockMvc.perform(post("/api/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isUnauthorized());

        verify(bookService, never()).create(any());
    }

    // writes are admin-only: an authenticated non-admin create is rejected with 403
    @Test
    void create_returns403_whenNotAdmin() throws Exception {
        mockMvc.perform(post("/api/books")
                        .with(user("bob").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isForbidden());

        verify(bookService, never()).create(any());
    }

    // An unfiltered catalogue read does not exist: callers must state a title search.
    @Test
    void search_requiresTitle() throws Exception {
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isBadRequest());

        verify(bookService, never()).search(any());
    }
}
