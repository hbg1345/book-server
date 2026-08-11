package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;

import io.gatling.javaapi.core.Simulation;

/**
 * <strong>Endurance / soak test</strong> — a modest load held for a long time.
 *
 * <p>The load is deliberately lower than {@link LoadSimulation}; the variable under test is
 * duration, not intensity. Faults that need hours to appear are the ones a one-hour run
 * cannot see: a heap that creeps up because something is retained per request, a connection
 * pool that leaks a connection on an error path, a cache with no eviction, a file handle
 * never closed.
 *
 * <p>The finding is rarely in Gatling's report — throughput usually looks flat right up until
 * the failure. <strong>Watch the server, not the client:</strong> Cloud Run container memory
 * over time and Cloud SQL active connections are where a leak shows itself. A memory curve
 * that only ever rises is the result, even if every request returned 200.
 *
 * <p>Defaults to 8 hours, which is what the definition calls for and what it takes to see a
 * slow leak. Pass {@code -Pduration=3600} for a 1-hour rehearsal — but know that a short soak
 * that finds nothing has not shown the absence of a leak.
 */
public class EnduranceSimulation extends Simulation {

    private final int users = LoadTestConfig.intProp("users", 20);
    private final java.time.Duration rampUp = LoadTestConfig.seconds("rampUp", 120);
    private final java.time.Duration duration = LoadTestConfig.seconds("duration", 8 * 60 * 60);

    {
        setUp(BookCatalog.browsingScenario("catalog-browsing-endurance").injectClosed(
                        rampConcurrentUsers(0).to(users).during(rampUp),
                        constantConcurrentUsers(users).during(duration)))
                .protocols(BookCatalog.httpProtocol())
                // Same bar as the load test: a soak that degrades has found something, and the
                // run should say so rather than leaving it to whoever reads the graphs.
                .assertions(
                        global().successfulRequests().percent().gt(99.0),
                        global().responseTime().percentile3().lt(1000));
    }

    @Override
    public void before() {
        BookCatalog.warmUp();
    }
}
