package com.example.bookserver.book;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit, read-only sampling tool for the production title catalogue.
 *
 * <p>Credentials are accepted only through process environment variables and are never written
 * to the report. The transaction and database session are both read-only, and the statement has
 * a short timeout. This class is excluded from the normal test task.
 */
@Tag("production-read")
class ProductionBookTitleSamplerTest {

    private static final int SAMPLE_SIZE = 250;
    private static final Path OUTPUT = Path.of(
            "build", "reports", "search-quality", "production-book-titles.csv");

    @Test
    void sampleProductionTitles() throws Exception {
        String url = requiredEnvironment("PRODUCTION_DATABASE_URL");
        String username = requiredEnvironment("PRODUCTION_DATABASE_USERNAME");
        String password = requiredEnvironment("PRODUCTION_DATABASE_PASSWORD");

        Files.createDirectories(OUTPUT.getParent());
        int sampled = 0;
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (PreparedStatement session = connection.prepareStatement(
                    "SET LOCAL statement_timeout = '10s'")) {
                session.execute();
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT book_uuid, book_title
                    FROM book TABLESAMPLE BERNOULLI (5) REPEATABLE (20260814)
                    WHERE book_title IS NOT NULL AND btrim(book_title) <> ''
                    ORDER BY md5(book_uuid::text || 'book-search-evaluation-v1')
                    LIMIT ?
                    """)) {
                query.setInt(1, SAMPLE_SIZE);
                try (ResultSet rows = query.executeQuery();
                     BufferedWriter output = Files.newBufferedWriter(
                             OUTPUT, StandardCharsets.UTF_8)) {
                    output.write("book_uuid,book_title\n");
                    while (rows.next()) {
                        output.write(csv(rows.getString("book_uuid")));
                        output.write(',');
                        output.write(csv(rows.getString("book_title")));
                        output.write('\n');
                        sampled++;
                    }
                }
            } finally {
                connection.rollback();
            }
        }

        assertThat(sampled).isEqualTo(SAMPLE_SIZE);
        System.out.printf("Sampled %d production book titles to %s%n",
                sampled, OUTPUT.toAbsolutePath());
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value).as("required environment variable %s", name).isNotBlank();
        return value;
    }

    private String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
