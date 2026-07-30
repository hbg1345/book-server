package com.example.bookserver.book.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.example.bookserver.book.Book;

/**
 * Book view returned to the client. {@code authors} is populated on the detail read
 * (GET /api/books/{uuid}); on the list read it is an empty list (authors not fetched).
 */
public record BookResponse(
        UUID bookUuid,
        String bookTitle,
        String bookDescription,
        BigDecimal price,
        LocalDate publishDate,
        String publisher,
        Integer inventory,
        List<AuthorResponse> authors) {

    public static BookResponse from(Book book) {
        List<AuthorResponse> authors = book.getAuthors() == null
                ? List.of()
                : book.getAuthors().stream().map(AuthorResponse::from).toList();
        return new BookResponse(
                book.getBookUuid(),
                book.getBookTitle(),
                book.getBookDescription(),
                book.getPrice(),
                book.getPublishDate(),
                book.getPublisher(),
                book.getInventory(),
                authors);
    }
}
