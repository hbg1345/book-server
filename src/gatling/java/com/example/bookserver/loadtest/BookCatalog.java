package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.csv;
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
 * The one thing every profile drives: reading the book catalogue. The five simulations differ
 * only in how many users arrive and for how long — the work they do is defined here once.
 *
 * <p><strong>Why the detail endpoint and not the list.</strong> {@code GET /api/books} has no
 * pagination and the catalogue holds ~103k rows, so a single call serialises the whole table.
 * Loading it would measure how fast the server runs out of memory, which is a defect to fix
 * rather than a performance characteristic to chart. {@code GET /api/books/{uuid}} is the
 * indexed single-row read that a real catalogue page issues, so it is what gets loaded here.
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
     * 2000 author names sampled from the same seed. Names are fed as a query parameter rather
     * than spliced into the path so Gatling percent-encodes them — most of this catalogue's
     * names are "Surname, Forename", and a raw comma or space in a URL is not a request anyone
     * meant to send.
     */
    private static FeederBuilder<String> authorNames() {
        return csv("data/author_names.csv").random();
    }

    /** The cheap read: a single row by primary key. */
    private static ChainBuilder openBookDetail() {
        return feed(bookUuids())
                .exec(http("GET /api/books/{bookUuid}")
                        .get(session -> "/api/books/" + session.getString("bookUuid"))
                        .check(status().is(200)));
    }

    /**
     * The expensive read, and the reason this profile exists.
     *
     * <p>{@code AuthorMapper.findByName} filters on {@code author_name}, which carries no index —
     * V1 indexed only the primary key and V2 merely widened the column to TEXT. Every search is
     * therefore a sequential scan of all 71,081 authors, and each match then costs a second
     * query for the author's book titles.
     *
     * <p>Loading only the primary-key read would report a ceiling this endpoint cannot meet, so
     * the mix exists to keep the measurement honest. It also makes the obvious fix measurable:
     * add an index on {@code author_name}, re-run, and the difference is the whole argument.
     */
    private static ChainBuilder searchAuthorByName() {
        return feed(authorNames())
                .exec(http("GET /api/authors?name=")
                        .get("/api/authors")
                        .queryParam("name", "#{authorName}")
                        .check(status().is(200)));
    }

    /** Browsing pace: a pause between requests, as a reader would take. */
    public static ScenarioBuilder readScenario(String name) {
        return readScenario(name, LoadTestConfig.THINK_TIME_MIN, LoadTestConfig.THINK_TIME_MAX);
    }

    /**
     * One user's loop: a weighted choice of read, then a pause. The weighting is what makes this
     * a load test rather than a benchmark — a real population does not spend all its time on the
     * cheapest endpoint, and a server sized against that assumption falls over on the mix.
     *
     * <p>Defaults to 70/30 detail/search. Set {@code -PauthorSearchPct=0} to go back to loading
     * the single-row read alone, or 100 to isolate the scan.
     */
    public static ScenarioBuilder readScenario(String name, Duration pauseMin, Duration pauseMax) {
        double searchPct = LoadTestConfig.AUTHOR_SEARCH_PCT;
        ChainBuilder reads = searchPct <= 0 ? openBookDetail()
                : searchPct >= 100 ? searchAuthorByName()
                : randomSwitch().on(
                        percent(100 - searchPct).then(openBookDetail()),
                        percent(searchPct).then(searchAuthorByName()));

        // Uniform random within the range, so users drift out of lockstep instead of arriving
        // as a pulse every N seconds.
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
