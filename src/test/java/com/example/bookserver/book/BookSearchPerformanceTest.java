package com.example.bookserver.book;

import com.example.bookserver.TestcontainersConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local full-catalogue query benchmark for the terms used by the page-depth Gatling scenarios.
 *
 * <p>It separates result retrieval from the bounded navigation probe so a regression cannot hide
 * behind one aggregate endpoint number. The deployed Gatling run remains the capacity gate; this
 * test is the cheaper pre-merge warning that was missing when fuzzy ranking was introduced.
 */
@MybatisTest
@Import(TestcontainersConfiguration.class)
@Tag("search-performance")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookSearchPerformanceTest {

    private static final List<String> BROAD_TERMS = List.of(
            "The", "Book", "Guide", "Men", "America", "Novel", "Life", "From",
            "King", "Story", "Series", "World", "American", "Read", "Let", "Edition",
            "Home");
    private static final int PAGE_SIZE = 20;
    private static final int NAVIGATION_LIMIT = 101;

    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void reportBroadSearchLatency() {
        Long catalogueSize = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book", Long.class);
        assertThat(catalogueSize).isNotNull().isGreaterThan(100_000L);

        System.out.println("\n=== LOCAL BOOK SEARCH INDEX COMPARISON ===");
        System.out.println("Catalogue rows: " + catalogueSize);
        for (IndexMode mode : IndexMode.values()) {
            configureIndexes(mode);
            printNearestNeighbourPlan(mode);

            // One untimed pass makes the comparison about steady-state query work rather than
            // the first disk reads and PostgreSQL plan-cache setup.
            for (String term : BROAD_TERMS) {
                measure(term, 0);
                measure(term, 100);
            }

            System.out.printf("%nindexMode=%s ginSize=%s gistSize=%s%n", mode,
                    indexSize("idx_book_title_trgm"),
                    indexSize("idx_book_title_trgm_gist"));
            reportPage(0);
            reportPage(100);
        }

        // Leave the shared context in the same two-index state Flyway creates,
        // in case another method is added to this class later.
        configureIndexes(IndexMode.BOTH);
    }

    private void configureIndexes(IndexMode mode) {
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_book_title_trgm");
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_book_title_trgm_gist");
        if (mode != IndexMode.GIST_ONLY) {
            jdbcTemplate.execute("""
                    CREATE INDEX idx_book_title_trgm
                    ON book USING GIN (book_title gin_trgm_ops)
                    """);
        }
        if (mode != IndexMode.GIN_ONLY) {
            jdbcTemplate.execute("""
                    CREATE INDEX idx_book_title_trgm_gist
                    ON book USING GIST (book_title gist_trgm_ops(siglen=32))
                    """);
        }
        jdbcTemplate.execute("ANALYZE book");
    }

    private void printNearestNeighbourPlan(IndexMode mode) {
        String plan = String.join("\n", jdbcTemplate.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT book_uuid
                FROM book
                WHERE 'The' <% book_title
                ORDER BY 'The' <<-> book_title, 'The' <-> book_title
                FETCH FIRST 20 ROWS WITH TIES
                """, String.class));
        System.out.println("\n--- " + mode + " representative KNN plan ---\n" + plan);
        if (mode != IndexMode.GIN_ONLY) {
            assertThat(plan).contains("idx_book_title_trgm_gist");
        }
    }

    private String indexSize(String indexName) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(pg_size_pretty(pg_relation_size(to_regclass(?))), 'absent')",
                String.class, indexName);
    }

    private void reportPage(int page) {
        List<Measurement> measurements = new ArrayList<>();
        for (String term : BROAD_TERMS) {
            measurements.add(measure(term, page));
        }

        System.out.printf(Locale.ROOT,
                "page=%d n=%d search[p50=%.1fms p95=%.1fms max=%.1fms] "
                        + "navigation[p50=%.1fms p95=%.1fms max=%.1fms] "
                        + "total[p50=%.1fms p95=%.1fms max=%.1fms]%n",
                page, measurements.size(),
                percentile(measurements, Measurement::searchMillis, 0.50),
                percentile(measurements, Measurement::searchMillis, 0.95),
                maximum(measurements, Measurement::searchMillis),
                percentile(measurements, Measurement::navigationMillis, 0.50),
                percentile(measurements, Measurement::navigationMillis, 0.95),
                maximum(measurements, Measurement::navigationMillis),
                percentile(measurements, Measurement::totalMillis, 0.50),
                percentile(measurements, Measurement::totalMillis, 0.95),
                maximum(measurements, Measurement::totalMillis));
    }

    private Measurement measure(String term, int page) {
        long offset = (long) page * PAGE_SIZE;
        long started = System.nanoTime();
        List<Book> books = bookMapper.searchByTitle(term, offset, PAGE_SIZE);
        long searchFinished = System.nanoTime();
        int navigationElements = bookMapper.countSearchWindow(term, offset, NAVIGATION_LIMIT);
        long finished = System.nanoTime();

        assertThat(books).as("content for term %s at page %s", term, page).hasSize(PAGE_SIZE);
        assertThat(navigationElements).as("navigation for term %s at page %s", term, page)
                .isPositive();
        return new Measurement(
                nanosToMillis(searchFinished - started),
                nanosToMillis(finished - searchFinished),
                nanosToMillis(finished - started));
    }

    private static double percentile(
            List<Measurement> measurements, Value value, double percentile) {
        List<Double> sorted = measurements.stream().map(value::get).sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static double maximum(List<Measurement> measurements, Value value) {
        return measurements.stream().map(value::get).max(Comparator.naturalOrder()).orElse(0.0);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    @FunctionalInterface
    private interface Value {
        double get(Measurement measurement);
    }

    private record Measurement(double searchMillis, double navigationMillis, double totalMillis) {
    }

    private enum IndexMode {
        GIN_ONLY,
        GIST_ONLY,
        BOTH
    }
}
