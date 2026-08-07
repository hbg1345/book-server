package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;

import io.gatling.javaapi.core.Simulation;

/**
 * <strong>Mixed traffic</strong> — browsers and buyers on the server at the same time, in the
 * proportion a shop actually sees.
 *
 * <p>The other profiles isolate one path, which is right for attributing a bottleneck and wrong
 * for predicting a Tuesday. Real traffic is mostly people looking, with a small share buying,
 * and the two are not independent: buyers hold row locks and database connections that browsers
 * are then queued behind. A server sized from a read-only run and a write-only run separately
 * has been sized against a population that does not exist.
 *
 * <p>The number to watch is <em>read</em> latency, not write. Checkout being slow under checkout
 * load is expected. Catalogue pages getting slow because other people are buying is the finding
 * — it means the connection pool, not the lock, is the constraint, and it is the failure a
 * shopper actually notices.
 *
 * <pre>
 * ./gradlew gatlingRun --simulation=…MixedTrafficSimulation -Pusers=200 -PbuyerPct=10
 * </pre>
 */
public class MixedTrafficSimulation extends Simulation {

    private final int users = LoadTestConfig.intProp("users", 200);
    private final double buyerPct = LoadTestConfig.doubleProp("buyerPct", 10);
    private final java.time.Duration rampUp = LoadTestConfig.seconds("rampUp", 60);
    private final java.time.Duration duration = LoadTestConfig.seconds("duration", 600);

    {
        int buyers = (int) Math.max(1, Math.round(users * buyerPct / 100));
        int browsers = Math.max(1, users - buyers);

        setUp(
                BookCatalog.readScenario("browsers").injectClosed(
                        rampConcurrentUsers(0).to(browsers).during(rampUp),
                        constantConcurrentUsers(browsers).during(duration)),
                Shoppers.checkoutScenario("buyers").injectClosed(
                        rampConcurrentUsers(0).to(buyers).during(rampUp),
                        constantConcurrentUsers(buyers).during(duration)))
                .protocols(BookCatalog.httpProtocol())
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        // The whole point of the profile: reads must stay fast while writes run.
                        // Loosen this only after deciding that a slow catalogue is acceptable.
                        details("GET /api/books/{bookUuid}").responseTime().percentile3().lt(1000));
    }

    @Override
    public void before() {
        BookCatalog.warmUp();
        Shoppers.topUpStock();
        Shoppers.provisionAccounts();
    }

    /** Give back the stock the run borrowed; this deployment is also the demo. */
    @Override
    public void after() {
        Shoppers.restoreStock();
    }
}
