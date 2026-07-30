package com.example.bookserver.book;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Book {

    private UUID bookUuid;
    private String bookTitle;
    private String bookDescription;
    private BigDecimal price;
    private LocalDate publishDate;
    private String publisher;
    private Integer inventory;

    private List<Author> authors;
}
