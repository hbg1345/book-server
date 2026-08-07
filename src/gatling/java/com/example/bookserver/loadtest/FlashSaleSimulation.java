package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.global;

import io.gatling.javaapi.core.Simulation;

/**
 * <strong>Flash sale</strong> — every user, one title, all at once, until it sells out.
 *
 * <p>This is the profile that checks correctness rather than speed. Everything else here asks
 * how fast the server answers; this asks whether the answers were <em>true</em>. A server can
 * be fast, return no errors, and still sell eleven copies of the ten it had — an oversell is a
 * 201 like any other, indistinguishable in the report from a sale that was real.
 *
 * <p>So the measurement is not in Gatling's output. It is the two numbers printed before and
 * after: stock beforehand, stock afterwards, and the count of orders that came back 201. If
 * they do not reconcile, {@code decrementInventory}'s conditional update let two transactions
 * past a check only one of them could still satisfy, and the concurrency tests were passing on
 * a thread count too small to catch it.
 *
 * <pre>
 * ./gradlew gatlingRun --simulation=…FlashSaleSimulation -Pusers=500
 * </pre>
 *
 * <p>Deliberately takes no admin credentials and tops nothing up: the shelf running out is the
 * point, not an accident to be engineered around. {@code -PthinkTime=0} is implied — a pause
 * would let the queue drain between arrivals, which is the one thing a flash sale does not do.
 *
 * <p><strong>The reconciliation is only valid for runs shorter than
 * {@code order.payment-timeout} (PT30M).</strong> Orders placed here sit in PAYMENT_PENDING
 * holding their reservations; past that window the expiry sweep cancels them and gives the stock
 * back, and the arithmetic below then reports a shortfall that is the sweeper doing its job
 * rather than a defect. This profile fires everyone at once and is over in seconds, so it is
 * well inside the window — but a run stretched past thirty minutes is measuring something else.
 */
public class FlashSaleSimulation extends Simulation {

    private final int users = LoadTestConfig.intProp("users", 500);

    private String theBook;
    private int stockBefore;

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
        theBook = Shoppers.theOneBook();
        stockBefore = Shoppers.reportStock("before", theBook);
    }

    @Override
    public void after() {
        int stockAfter = Shoppers.reportStock("after", theBook);
        System.out.println();
        System.out.println("  stock before : " + stockBefore);
        System.out.println("  stock after  : " + stockAfter);
        System.out.println("  sold         : " + (stockBefore - stockAfter));
        System.out.println();
        System.out.println("  Compare 'sold' against the count of 201s on POST /api/orders in the");
        System.out.println("  report. They must be equal, and stock after must never be negative.");
        System.out.println("  A mismatch is an oversell: two transactions passed a stock check");
        System.out.println("  that only one of them could still satisfy.");

        if (stockAfter < 0) {
            throw new AssertionError("stock went negative (" + stockAfter
                    + "): the shop sold copies it did not have");
        }
    }
}
