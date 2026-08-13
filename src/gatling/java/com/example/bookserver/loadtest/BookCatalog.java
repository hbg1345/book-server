package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Catalogue requests shared by every load profile. The application exposes two ordinary reader
 * actions: search by title and open one book. Keeping those actions here lets endpoint-only and
 * mixed simulations exercise identical HTTP requests.
 */
public final class BookCatalog {

    private BookCatalog() {
    }

    /** Catalogue reads are public (see SecurityConfig), so no token handling is needed. */
    public static HttpProtocolBuilder httpProtocol() {
        HttpProtocolBuilder protocol = http.baseUrl(LoadTestConfig.BASE_URL)
                .acceptHeader("application/json")
                .userAgentHeader("gatling/book-server-loadtest")
                .disableCaching();

        // Share one connection pool across all virtual users instead of giving each its own.
        //
        // Per-user pools model separate browsers faithfully, but every user then needs its own
        // socket, and a socket needs a local port. Windows hands out roughly 16k ephemeral ports
        // and holds each in TIME_WAIT for minutes after close, so a few thousand users churning
        // connections exhausts the range and Netty starts throwing
        // "BindException: Address already in use". That failure is the load generator hitting its
        // own limit — it says nothing about the server, but it looks exactly like the server
        // refusing connections, which is the single easiest way to misread this whole exercise.
        //
        // Sharing also matches how real clients behave: browsers keep connections alive and reuse
        // them rather than reconnecting per request. The cost is that TLS handshake time largely
        // drops out of the measurement; set -PshareConnections=false when that is the thing being
        // measured, and expect to need far fewer users.
        return LoadTestConfig.SHARE_CONNECTIONS ? protocol.shareConnections() : protocol;
    }

    /**
     * 2000 book uuids sampled from the V3 catalogue seed, so every request hits a row that
     * exists. {@code random()} rather than {@code circular()}: a circular feeder marches all
     * users through the same keys in the same order, which turns PostgreSQL's cache into a
     * single hot page and reports a latency the real access pattern would never see.
     */
    private static FeederBuilder<String> bookUuids() {
        return csv("data/book_uuids.csv").random();
    }

    /**
     * Search tokens derived from the same 2000 seeded books used by the detail feeder.
     * Each row also carries its result-count band for reporting; the terms, order, duplicates,
     * and therefore their random selection probabilities remain unchanged.
     */
    private static FeederBuilder<String> bookSearchTerms() {
        return csv("data/book_search_terms.csv").random();
    }

    /**
     * Broad terms with more than 2,200 matches in the seeded catalogue. Both sides of the
     * page-0/page-100 comparison use this exact feeder, so the only changed input is OFFSET.
     */
    private static FeederBuilder<String> deepSearchTerms() {
        return csv("data/book_search_deep_terms.csv").random();
    }

    /** The indexed book lookup used after a reader selects a result. */
    private static ChainBuilder openBookDetail() {
        return feed(bookUuids())
                .exec(http("GET /api/books/{bookUuid}")
                        .get(session -> "/api/books/" + session.getString("bookUuid"))
                        .check(status().is(200)));
    }

    /**
     * User-facing title search: a case-insensitive substring over the catalogue.
     *
     * <p>{@code ILIKE '%term%'} cannot use an ordinary B-tree index, so this is intentionally the
     * expensive request in the browsing mix. Feeding varied tokens avoids measuring one cached
     * search repeatedly, while every term is guaranteed to occur in seeded data.
     */
    private static ChainBuilder searchBooksByTitle() {
        return feed(bookSearchTerms())
                .exec(http(session -> "GET /api/books?title=["
                                + session.getString("searchBand") + "]")
                        .get("/api/books")
                        .queryParam("title", "#{bookSearchTerm}")
                        .queryParam("page", "0")
                        .check(status().is(200)));
    }

    private static ChainBuilder searchBroadTitlesAtPage(int page) {
        return feed(deepSearchTerms())
                .exec(http("GET /api/books?title=[broad]&page=" + page)
                        .get("/api/books")
                        .queryParam("title", "#{bookSearchTerm}")
                        .queryParam("page", Integer.toString(page))
                        .check(status().is(200)));
    }

    public static ScenarioBuilder titleSearchScenario(String name) {
        return titleSearchScenario(name, LoadTestConfig.THINK_TIME_MIN, LoadTestConfig.THINK_TIME_MAX);
    }

    public static ScenarioBuilder titleSearchScenario(String name, Duration pauseMin, Duration pauseMax) {
        return scenario(name).exec(searchBooksByTitle()).pause(pauseMin, pauseMax);
    }

    /** Same broad terms as the deep-page scenario, but with no rows skipped. */
    public static ScenarioBuilder titleSearchFirstPageComparisonScenario(String name) {
        return scenario(name)
                .exec(searchBroadTitlesAtPage(0));
    }

    /** Broad terms at a configurable deep page, page 100 by default. */
    public static ScenarioBuilder titleSearchDeepPageComparisonScenario(String name) {
        return scenario(name)
                .exec(searchBroadTitlesAtPage(LoadTestConfig.SEARCH_DEEP_PAGE));
    }

    public static ScenarioBuilder bookDetailScenario(String name) {
        return bookDetailScenario(name, LoadTestConfig.THINK_TIME_MIN, LoadTestConfig.THINK_TIME_MAX);
    }

    public static ScenarioBuilder bookDetailScenario(String name, Duration pauseMin, Duration pauseMax) {
        return scenario(name).exec(openBookDetail()).pause(pauseMin, pauseMax);
    }

    /** Browsing pace with the configurable list/search/detail request mix. */
    public static ScenarioBuilder browsingScenario(String name) {
        return browsingScenario(name, LoadTestConfig.THINK_TIME_MIN, LoadTestConfig.THINK_TIME_MAX);
    }

    /**
     * One virtual user's action, selected from the catalogue mix, followed by a reading pause.
     * Defaults to 50% title search and 50% detail. The endpoint-only simulations are the right
     * tool for isolated capacity; this mix is for shared-resource contention.
     */
    public static ScenarioBuilder browsingScenario(String name, Duration pauseMin, Duration pauseMax) {
        ChainBuilder reads = randomSwitch().on(
                percent(LoadTestConfig.TITLE_SEARCH_PCT).then(searchBooksByTitle()),
                percent(LoadTestConfig.BOOK_DETAIL_PCT).then(openBookDetail()));

        return scenario(name).exec(reads).pause(pauseMin, pauseMax);
    }

    /**
     * Wakes a scaled-to-zero revision before the run starts. Deliberately plain JDK HTTP and
     * not a Gatling step: anything Gatling executes is recorded, and the cold start would then
     * sit in the very percentiles the run exists to report.
     *
     * <p>Failures here are ignored on purpose — this is a courtesy call, and if the server is
     * genuinely unreachable the simulation itself is the honest place for that to surface.
     */
    public static void warmUp() {
        if (LoadTestConfig.WARMUP_REQUESTS <= 0) {
            return;
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LoadTestConfig.BASE_URL + "/api/books/00000000-0000-0000-0000-000000000000"))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        System.out.printf("warming up %s with %d request(s)…%n",
                LoadTestConfig.BASE_URL, LoadTestConfig.WARMUP_REQUESTS);
        for (int i = 0; i < LoadTestConfig.WARMUP_REQUESTS; i++) {
            try {
                // Any response proves the revision is up and the JVM is JIT-ing; a 404 for the
                // nil uuid is the expected one and is just as good a wake-up as a 200.
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                System.out.printf("  warm-up %d/%d -> %d%n", i + 1, LoadTestConfig.WARMUP_REQUESTS, response.statusCode());
            } catch (IOException e) {
                System.out.printf("  warm-up %d/%d -> %s%n", i + 1, LoadTestConfig.WARMUP_REQUESTS, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
