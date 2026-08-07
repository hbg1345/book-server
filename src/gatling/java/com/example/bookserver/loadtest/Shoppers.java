package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.jmesPath;
import static io.gatling.javaapi.core.CoreDsl.listFeeder;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The write path: signing in, filling a cart, placing an order. {@link BookCatalog} is the
 * counterpart for reads.
 *
 * <p><strong>Why this exists.</strong> The read profiles load a single indexed row and an
 * unindexed scan — real work, but work with no contention in it. Nothing there takes a row lock,
 * so throughput degrades smoothly until the CPU runs out and the graph is a straight line.
 * Checkout is where this application actually gets interesting: {@code placeOrder} claims the
 * cart {@code FOR UPDATE}, then decrements stock with a conditional update per line. Two users
 * buying the same book serialise on the same row, and the cost of that is invisible to any test
 * that only reads.
 *
 * <p><strong>The knob that matters is {@code -PhotBooks}.</strong> It sets how many distinct
 * titles the population draws from, which is the same thing as how hard they collide:
 *
 * <pre>
 *   -PhotBooks=2000   spread out; the lock is essentially never contended  (baseline)
 *   -PhotBooks=100    a bestseller list; some queuing
 *   -PhotBooks=1      one title, every user                                (flash sale)
 * </pre>
 *
 * <p>Holding the user count fixed and sweeping that number is the measurement — throughput
 * against contention, rather than throughput against concurrency. The second is the graph
 * everyone draws; the first is the one that explains it.
 */
public final class Shoppers {

    private Shoppers() {
    }

    /** Distinct titles the population buys from. See the class javadoc. */
    public static final int HOT_BOOKS = LoadTestConfig.intProp("hotBooks", 2000);

    /** Virtual accounts provisioned before the run. */
    public static final int ACCOUNTS = LoadTestConfig.intProp("accounts", 200);

    /** Copies per order line. One is the common case; raising it exhausts stock faster. */
    public static final int QUANTITY = LoadTestConfig.intProp("quantity", 1);

    /**
     * Admin credentials, used only to top stock up before a run. Optional: without them the
     * run proceeds against whatever stock the catalogue happens to hold, which for the seed is
     * 100 copies of everything — enough to be exhausted in seconds by a checkout profile, at
     * which point every request 409s and the graph reports a server that is fine.
     */
    public static final String ADMIN_ID = System.getProperty("adminId", "");
    public static final String ADMIN_PASSWORD = System.getProperty("adminPassword", "");

    /**
     * Stock added to each hot title before the run, and taken back off after it.
     *
     * <p>Enough that a long run does not sell out, small enough that the catalogue still looks
     * like a catalogue if the restore never happens. This is a demo deployment rather than a
     * throwaway environment — somebody may well open it and look at it — so a title left
     * holding a million copies is a worse outcome than a run that ran short.
     */
    public static final int STOCK_TOP_UP = LoadTestConfig.intProp("stockTopUp", 50_000);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /** Tokens minted in {@link #provisionAccounts()}, handed to virtual users round-robin. */
    private static final List<Map<String, Object>> TOKENS = new ArrayList<>();

    /** Admin access token, minted once by {@link #mintAdminToken()}. */
    private static String adminToken;

    // ------------------------------------------------------------------ feeders

    /**
     * The titles this run buys, narrowed to {@link #HOT_BOOKS}.
     *
     * <p>Read eagerly and re-fed from a list rather than {@code csv(...).random()}, because the
     * point is to control how many distinct keys are in play — a random feeder over the whole
     * file always spreads across all 2000 and contention can never be dialled up.
     */
    public static FeederBuilder<Object> hotBooks() {
        List<Map<String, Object>> all = csv("data/book_uuids.csv").readRecords();
        List<Map<String, Object>> hot = all.subList(0, Math.min(HOT_BOOKS, all.size()));
        return listFeeder(hot).random();
    }

    /**
     * One access token per virtual user, drawn at random.
     *
     * <p>Tokens are minted once up front rather than by logging in inside the scenario. A login
     * costs a BCrypt verification — deliberately expensive, tens of milliseconds of pure CPU —
     * and a checkout profile that signs in on every iteration spends most of its budget there,
     * reporting BCrypt's throughput under the name of the order path. Authentication is worth
     * loading, but as its own profile and not as a tax on this one.
     */
    public static FeederBuilder<Object> tokens() {
        if (TOKENS.isEmpty()) {
            throw new IllegalStateException(
                    "no tokens: call Shoppers.provisionAccounts() from the simulation's before()");
        }
        return listFeeder(TOKENS).random();
    }

    // ------------------------------------------------------------------ chains

    /** Bearer header from the fed token. */
    private static ChainBuilder authenticate() {
        return feed(tokens());
    }

    /** Put one title in the cart. Insert-or-increment, so repeating it is not an error. */
    public static ChainBuilder addToCart() {
        return feed(hotBooks())
                .exec(http("POST /api/cart/items")
                        .post("/api/cart/items")
                        .header("Authorization", "Bearer #{accessToken}")
                        .header("Content-Type", "application/json")
                        .body(io.gatling.javaapi.core.CoreDsl.StringBody(
                                "{\"bookUuid\":\"#{bookUuid}\",\"quantity\":" + QUANTITY + "}"))
                        .check(status().is(200)));
    }

    /**
     * Turn the cart into an order. This is the contended step: the cart rows are claimed
     * {@code FOR UPDATE} and stock is decremented per line.
     *
     * <p>409 is accepted as a valid outcome, not a failure. Once a title sells out the correct
     * answer is a refusal, and counting those as errors would report a broken server at exactly
     * the moment it is behaving. A real failure here is a 5xx or a timeout — the server failing
     * to arbitrate at all, rather than arbitrating against the caller.
     */
    public static ChainBuilder placeOrder() {
        return exec(http("POST /api/orders")
                .post("/api/orders")
                .header("Authorization", "Bearer #{accessToken}")
                .header("Content-Type", "application/json")
                .body(io.gatling.javaapi.core.CoreDsl.StringBody("""
                        {"address":{"recipient":"Load Test","phone":"010-0000-0000",
                         "country":"KR","roadAddress":"Sejong-daero 110","detailAddress":"5F",
                         "postalCode":"04524"}}
                        """))
                .check(status().in(201, 409))
                // optional: a 409 body has no purchaseUuid, and that is a valid outcome here
                .check(jmesPath("purchaseUuid").optional().saveAs("purchaseUuid")));
    }

    /** Open the payment intent, the step a real checkout takes next. */
    public static ChainBuilder openPaymentIntent() {
        return exec(session -> session)
                .doIf(session -> session.contains("purchaseUuid")).then(
                        exec(http("POST /api/orders/{id}/payment-intent")
                                .post(session -> "/api/orders/" + session.getString("purchaseUuid")
                                        + "/payment-intent")
                                .header("Authorization", "Bearer #{accessToken}")
                                .check(status().in(200, 409))));
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * Browse a little, then buy: the shape of a session that ends in an order.
     *
     * <p>The pause sits before the order rather than after, because that is where a real one is
     * — the user fills in an address — and because it is the gap during which somebody else may
     * take the last copy.
     */
    public static ScenarioBuilder checkoutScenario(String name) {
        return scenario(name)
                .exec(authenticate())
                .exec(addToCart())
                .pause(LoadTestConfig.THINK_TIME_MIN, LoadTestConfig.THINK_TIME_MAX)
                .exec(placeOrder());
    }

    /** Checkout, then the payment intent — the full path a paying customer walks. */
    public static ScenarioBuilder payingScenario(String name) {
        return scenario(name)
                .exec(authenticate())
                .exec(addToCart())
                .pause(LoadTestConfig.THINK_TIME_MIN, LoadTestConfig.THINK_TIME_MAX)
                .exec(placeOrder())
                .exec(openPaymentIntent());
    }

    /**
     * Order with no pause at all: the queue at the lock, with nothing else in the measurement.
     * Used by the flash sale, where think time would only blur the thing being looked at.
     */
    public static ScenarioBuilder rushScenario(String name) {
        return scenario(name)
                .exec(authenticate())
                .exec(addToCart())
                .exec(placeOrder());
    }

    // ------------------------------------------------------------------ setup

    /**
     * Register {@link #ACCOUNTS} accounts and log each in, keeping the access tokens.
     *
     * <p>Plain JDK HTTP and not Gatling steps, for the same reason the warm-up is: anything
     * Gatling executes lands in the report, and several hundred BCrypt registrations would
     * dominate every percentile in it.
     *
     * <p>Ids carry the run's start time so repeated runs do not collide on
     * {@code book_user.user_id UNIQUE}. The accounts are left behind afterwards — cleaning them
     * up needs a delete endpoint that does not exist, and a load-test database that accumulates
     * rows is closer to a real one anyway.
     */
    public static void provisionAccounts() {
        if (!TOKENS.isEmpty()) {
            return;
        }
        long stamp = System.currentTimeMillis();
        System.out.printf("provisioning %d account(s)…%n", ACCOUNTS);
        for (int i = 0; i < ACCOUNTS; i++) {
            String userId = "lt-" + stamp + "-" + i;
            post("/api/users", """
                    {"userId":"%s","password":"secret1234","userName":"Load Test",
                     "phone":"010-0000-0000","birthDate":"2000-01-01"}
                    """.formatted(userId));
            String body = post("/api/auth/login",
                    "{\"userId\":\"%s\",\"password\":\"secret1234\"}".formatted(userId));
            String token = extract(body, "accessToken");
            if (token != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("accessToken", token);
                TOKENS.add(row);
            }
        }
        System.out.printf("  %d token(s) ready%n", TOKENS.size());
        if (TOKENS.isEmpty()) {
            throw new IllegalStateException("could not provision any account; is "
                    + LoadTestConfig.BASE_URL + " reachable and accepting registrations?");
        }
    }

    /**
     * Log the admin in and keep the token. Returns null when no credentials were supplied,
     * which the profiles that only want stock topped up treat as "carry on regardless".
     */
    public static String mintAdminToken() {
        if (adminToken != null) {
            return adminToken;
        }
        if (ADMIN_ID.isBlank() || ADMIN_PASSWORD.isBlank()) {
            return null;
        }
        String login = post("/api/auth/login",
                "{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN_ID, ADMIN_PASSWORD));
        adminToken = extract(login, "accessToken");
        return adminToken;
    }

    /** The token {@link #mintAdminToken()} obtained, or null. */
    public static String adminToken() {
        return adminToken;
    }

    /**
     * Add {@link #STOCK_TOP_UP} copies to every hot title, so the run measures the lock rather
     * than the shelf running out. Skipped silently when no admin credentials are given — the
     * flash sale profile wants the shelf to run out and passes none.
     */
    public static void topUpStock() {
        String token = mintAdminToken();
        if (token == null) {
            System.out.println("no admin credentials (-PadminId/-PadminPassword): "
                    + "running against existing stock");
            return;
        }
        adjustHotTitles(token, STOCK_TOP_UP, "topping up");
    }

    /**
     * Take back what {@link #topUpStock()} added, so the catalogue is left roughly as it was
     * found. The endpoint takes a change rather than a total, so the reversal is the same call
     * with the sign flipped — no reading of the current figure, and nothing lost if copies sold
     * in between.
     *
     * <p>Roughly, not exactly: whatever the run bought stays bought, and orders the sweeper has
     * not yet expired are still holding their reservations. Restoring exactly would mean waiting
     * on the sweeper, which is a longer wait than this is worth.
     */
    public static void restoreStock() {
        String token = adminToken();
        if (token == null) {
            return;
        }
        adjustHotTitles(token, -STOCK_TOP_UP, "restoring");
    }

    private static void adjustHotTitles(String token, int delta, String what) {
        List<Map<String, Object>> hot = csv("data/book_uuids.csv").readRecords();
        int count = Math.min(HOT_BOOKS, hot.size());
        System.out.printf("%s %d title(s) by %+d (%+d copies in total)…%n",
                what, count, delta, (long) count * delta);
        for (int i = 0; i < count; i++) {
            String bookUuid = String.valueOf(hot.get(i).get("bookUuid"));
            // A 409 here means the title holds fewer copies than we are taking back, which is
            // what a sell-out during the run looks like. Nothing to do about it and nothing
            // worth failing the run over.
            post("/api/books/" + bookUuid + "/stock", "{\"delta\":" + delta + "}", token);
        }
    }

    /** The first hot title, for profiles that need to name one. */
    public static String theOneBook() {
        return String.valueOf(csv("data/book_uuids.csv").readRecords().get(0).get("bookUuid"));
    }

    // ------------------------------------------------------------------ tiny HTTP helpers

    private static String post(String path, String json) {
        return post(path, json, null);
    }

    private static String post(String path, String json, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(LoadTestConfig.BASE_URL + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return send(b.build());
    }

    private static String get(String path) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(LoadTestConfig.BASE_URL + path))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build());
    }

    private static String send(HttpRequest request) {
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (java.io.IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Good enough for flat setup responses; not a JSON parser and not pretending to be one. */
    private static String extract(String json, String field) {
        if (json == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"?([^\",}]+)\"?")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
