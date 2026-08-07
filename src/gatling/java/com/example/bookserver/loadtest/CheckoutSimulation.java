package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;

import io.gatling.javaapi.core.Simulation;

/**
 * <strong>Checkout under steady load</strong> — {@link LoadSimulation}'s question asked of the
 * write path.
 *
 * <p>Reads have no lock in them; this does. {@code placeOrder} claims the caller's cart rows
 * {@code FOR UPDATE} and then decrements stock with a conditional update per line, so two users
 * buying the same title queue behind one another inside a transaction. That queue is the thing
 * being measured, and no amount of read load will show it.
 *
 * <p><strong>The run to do is a sweep, not a single execution.</strong> Hold the users fixed and
 * vary how many titles they compete for:
 *
 * <pre>
 * ./gradlew gatlingRun --simulation=…CheckoutSimulation -Pusers=100 -PhotBooks=2000
 * ./gradlew gatlingRun --simulation=…CheckoutSimulation -Pusers=100 -PhotBooks=100
 * ./gradlew gatlingRun --simulation=…CheckoutSimulation -Pusers=100 -PhotBooks=10
 * </pre>
 *
 * <p>Three points on a curve of throughput against contention. Concurrency is held constant
 * throughout, so whatever moves is the lock and not the load — which is the difference between
 * a graph that shows a server slowing down and one that explains why.
 *
 * <p>Supply {@code -PadminId}/{@code -PadminPassword} to top stock up first. Without it the
 * seeded 100 copies per title are gone in seconds and the rest of the run measures how quickly
 * the server can say 409.
 */
public class CheckoutSimulation extends Simulation {

    private final int users = LoadTestConfig.intProp("users", 50);
    private final java.time.Duration rampUp = LoadTestConfig.seconds("rampUp", 60);
    private final java.time.Duration duration = LoadTestConfig.seconds("duration", 600);

    {
        setUp(Shoppers.checkoutScenario("checkout").injectClosed(
                        rampConcurrentUsers(0).to(users).during(rampUp),
                        constantConcurrentUsers(users).during(duration)))
                .protocols(BookCatalog.httpProtocol())
                .assertions(
                        // No 5xx: a sold-out title is a 409 and expected, but nothing here should
                        // ever fail to be answered. Deadlocks and pool exhaustion surface here.
                        global().failedRequests().percent().lt(1.0),
                        // The order write is allowed to be slower than a read, but not unbounded.
                        // If this trips while /api/cart/items stays fast, the queue is the lock.
                        details("POST /api/orders").responseTime().percentile3().lt(3000));
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
