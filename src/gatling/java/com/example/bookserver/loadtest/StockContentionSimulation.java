package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.time.Duration;

/**
 * <strong>Stock adjustment against sales</strong> — an admin receiving copies while customers
 * buy them, both writing the same column.
 *
 * <p>Two paths write {@code book.inventory}: {@code decrementInventory} on the checkout path and
 * {@code adjustInventory} behind {@code POST /api/books/{uuid}/stock}. Both are relative and
 * conditional, so neither can erase the other — that is the property this profile exists to
 * hold under load rather than under three threads in a unit test.
 *
 * <p>The arithmetic is the assertion, and it is exact. Start at S, add R by adjustment, sell C
 * copies, and the shelf must read S + R − C. Anything else means one of the two paths read a
 * figure, worked from it, and wrote it back — the bug that turned a title edit into free stock,
 * arriving through a different door.
 *
 * <p>Adjustments are paced rather than hammered because that is what an admin does: a receipt
 * every few seconds against a continuous stream of sales. Contention here is not about volume
 * but about interleaving.
 *
 * <pre>
 * ./gradlew gatlingRun --simulation=…StockContentionSimulation \
 *     -Pusers=100 -PhotBooks=1 -PadminId=admin -PadminPassword=…
 * </pre>
 *
 * <p>Admin credentials are required, not optional: without them there is nothing to contend
 * with and the profile is just {@link CheckoutSimulation} on one title.
 */
public class StockContentionSimulation extends Simulation {

    private final int buyers = LoadTestConfig.intProp("users", 100);
    private final int admins = LoadTestConfig.intProp("admins", 3);
    private final int delta = LoadTestConfig.intProp("adjustDelta", 50);
    private final Duration adjustEvery = LoadTestConfig.seconds("adjustEvery", 3);
    private final Duration duration = LoadTestConfig.seconds("duration", 300);

    /**
     * Resolved here rather than in {@link #before()} because the scenario below splices it into
     * a URL while the simulation is being constructed, which happens first.
     */
    private final String theBook = Shoppers.theOneBook();

    private int stockBefore;

    /**
     * One admin's loop: receive {@code delta} copies, on a fixed cadence. {@code pace} rather
     * than {@code pause} so the rate holds steady even when the request itself is slow — if the
     * adjustment starts queueing behind sales, the interval should absorb it rather than the
     * rate quietly dropping and hiding the contention.
     */
    private ScenarioBuilder adminScenario() {
        return scenario("stock-adjustments")
                .exec(session -> session.set("adminToken", Shoppers.adminToken()))
                .forever().on(
                        pace(adjustEvery)
                                .exec(http("POST /api/books/{id}/stock")
                                        .post("/api/books/" + theBook + "/stock")
                                        .header("Authorization", "Bearer #{adminToken}")
                                        .header("Content-Type", "application/json")
                                        .body(io.gatling.javaapi.core.CoreDsl.StringBody(
                                                "{\"delta\":" + delta + "}"))
                                        .check(status().is(200))));
    }

    {
        setUp(
                Shoppers.checkoutScenario("buyers").injectClosed(
                        constantConcurrentUsers(buyers).during(duration)),
                adminScenario().injectClosed(
                        constantConcurrentUsers(admins).during(duration)))
                .protocols(BookCatalog.httpProtocol())
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        // Adjustments only ever add here, so any refusal means the write could
                        // not get through at all rather than being legitimately declined.
                        details("POST /api/books/{id}/stock").successfulRequests().percent().is(100.0));
    }

    @Override
    public void before() {
        if (Shoppers.mintAdminToken() == null) {
            throw new IllegalStateException(
                    "this profile needs -PadminId and -PadminPassword: without an admin there is "
                            + "no second writer, and it degenerates into CheckoutSimulation");
        }
        BookCatalog.warmUp();
        Shoppers.provisionAccounts();
        stockBefore = Shoppers.reportStock("before", theBook);
    }

    @Override
    public void after() {
        int stockAfter = Shoppers.reportStock("after", theBook);
        System.out.println();
        System.out.println("  stock before : " + stockBefore);
        System.out.println("  stock after  : " + stockAfter);
        System.out.println();
        System.out.println("  Check: stockAfter == stockBefore");
        System.out.println("                     + (" + delta + " x count of 200s on POST .../stock)");
        System.out.println("                     - (quantity x count of 201s on POST /api/orders)");
        System.out.println();
        System.out.println("  Both writers are relative and conditional, so the figure is exact.");
        System.out.println("  A shortfall means a sale was overwritten; a surplus means an");
        System.out.println("  adjustment was applied twice.");

        if (stockAfter < 0) {
            throw new AssertionError("stock went negative (" + stockAfter + ")");
        }
    }
}
