package db.migration;

import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

/**
 * Seeds the book catalog from pre-normalized, gzipped CSV files under {@code db/seed}
 * (generated offline from {@code archive/BooksDatasetClean.csv}: rows missing any
 * required field dropped, authors parsed/deduplicated, UUIDs assigned).
 *
 * <p>Loaded with Postgres {@code COPY} for speed — roughly 103k books, 72k authors and
 * 137k book-author links. Like every migration it runs exactly once on a fresh database
 * (local {@code bootRun}/docker and the prod Cloud SQL instance) and is recorded in
 * {@code flyway_schema_history}. Runs after V2 so the {@code category} column exists.
 */
public class V3__Seed_book_catalog extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        CopyManager copy = context.getConnection()
                .unwrap(PGConnection.class)
                .getCopyAPI();

        // FK order: category (self-referencing; the file is ordered parents-first) and
        // author have no external dependency; book references category; book_author
        // references book and author.
        copyGz(copy, "db/seed/categories.csv.gz",
                "COPY category (category_uuid, parent_uuid, name) "
                        + "FROM STDIN WITH (FORMAT csv, HEADER true)");
        copyGz(copy, "db/seed/authors.csv.gz",
                "COPY author (author_uuid, author_name) "
                        + "FROM STDIN WITH (FORMAT csv, HEADER true)");
        copyGz(copy, "db/seed/books.csv.gz",
                "COPY book (book_uuid, book_title, book_description, category_uuid, price, "
                        + "publish_date, publisher, inventory) "
                        + "FROM STDIN WITH (FORMAT csv, HEADER true)");
        copyGz(copy, "db/seed/book_authors.csv.gz",
                "COPY book_author (book_uuid, author_uuid) "
                        + "FROM STDIN WITH (FORMAT csv, HEADER true)");
    }

    private void copyGz(CopyManager copy, String resource, String sql) throws Exception {
        try (InputStream raw = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (raw == null) {
                throw new IllegalStateException("Seed resource not found on classpath: " + resource);
            }
            try (GZIPInputStream gz = new GZIPInputStream(raw)) {
                copy.copyIn(sql, gz);
            }
        }
    }
}
