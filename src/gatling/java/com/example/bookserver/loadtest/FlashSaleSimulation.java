package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.global;

import io.gatling.javaapi.core.Simulation;

/**
 * <strong>Flash sale</strong> — every user, one title, all at once, until it sells out.
 *
 * <p>The question is what a stampede costs, not whether the arbitration is correct — that is
 * {@code PurchaseConcurrencyTest}'s job and it already does it. Here every user queues on one
 * row, so the throughput is whatever a single row lock can be cycled at, and the interesting
 * numbers are the latency spread and whether anything times out on the way.
 *
 * <pre>
 * ./gradlew gatlingRun --simulation=…FlashSaleSimulation -Pusers=500 -PhotBooks=1 -PthinkTime=0
 * </pre>
 *
 * <p>Deliberately takes no admin credentials and tops nothing up: the shelf running out is the
 * point, not an accident to be engineered around. Think time should be zero — a pause would let
 * the queue drain between arrivals, which is the one thing a flash sale does not do.
 */
public class FlashSaleSimulation extends Simulation {

    private final int users = LoadTestConfig.intProp("users", 500);

    {
        setUp(Shoppers.rushScenario("flash-sale").injectOpen(atOnceUsers(users)))
                .protocols(BookCatalog.httpProtocol())
                .assertions(
                        // Every request must be answered. A 409 is an answer; a 500 or a timeout
                        // is the server failing to arbitrate, which is the failure that matters.
                        global().failedRequests().percent().lt(1.0));
    }

    @Override
    public void before() {
        if (Shoppers.HOT_BOOKS != 1) {
            System.out.println("note: a flash sale is one title — run with -PhotBooks=1");
        }
        BookCatalog.warmUp();
        Shoppers.provisionAccounts();
    }
}
