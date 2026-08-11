package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;

import io.gatling.javaapi.core.Simulation;

/**
 * <strong>BreakPoint test</strong> — concurrency climbs steadily until something gives.
 *
 * <p>One long ramp with no plateau. Every other profile needs a number to be aimed at, and
 * this is the run that produces it: the point where latency turns upward and errors begin is
 * this server's capacity. Run it <em>first</em> — a load test at 50 users only means something
 * once 50 is known to be half of what the server survives rather than a figure someone typed.
 *
 * <p><strong>Reading the result.</strong> The break is a shape on the response-time-over-time
 * graph, not a line in the summary. Latency stays flat while the server has headroom, then
 * bends upward as queueing begins; the concurrency at that bend is the answer, and it arrives
 * before the errors do. Cross-reference the x-axis with the injection ramp to convert elapsed
 * time back into a user count.
 *
 * <p><strong>Check what actually broke.</strong> If Gatling reports timeouts and rising
 * latency but Cloud Run's own metrics look healthy, the limit found is the load generator or
 * the home network link, not the server. A laptop on a domestic connection will usually hit
 * its own ceiling first — that is a measurement of the uplink, and reporting it as server
 * capacity is the classic way to get this test wrong. Re-run from a VM in the same region
 * before believing a low number.
 *
 * <p>Set {@code -PthinkTime=0} to measure raw throughput capacity instead of realistic user
 * capacity; the two answer different questions and produce very different numbers.
 *
 * <p><strong>Prefer the throughput figure over the user count when reporting.</strong> A user
 * count only means something alongside the think time it was measured with — halve the pause
 * and the same server "supports" half as many users. Requests per second carries no such
 * hidden premise, which is why the "Response Time against Global Throughput" chart on the
 * request detail page is the one to read.
 *
 * <p>No assertions — this simulation is supposed to end in failure.
 */
public class BreakPointSimulation extends Simulation {

    // 500 users over 120s — one added every 0.24s. This is a starting search range, not a claimed
    // capacity: raise maxUsers and re-run if the latency curve is still flat at the end.
    //
    // 0.24s between arrivals is fast but not too fast. The floor on ramp speed is Cloud Run's
    // scale-out: a new instance needs seconds to start a JVM, and pouring users in faster than
    // that measures how quickly instances appear rather than what they can serve once they have.
    private final int maxUsers = LoadTestConfig.intProp("maxUsers", 500);
    private final java.time.Duration duration = LoadTestConfig.seconds("duration", 120);

    {
        setUp(BookCatalog.browsingScenario("catalog-browsing-breakpoint",
                        // No pause, unlike every other profile. A think time turns user count into
                        // a figure that only means something next to the pause it was measured
                        // with; with none, concurrent users are concurrent requests and the result
                        // is a throughput ceiling that needs no such footnote.
                        LoadTestConfig.thinkTimeMin(0), LoadTestConfig.thinkTimeMax(0))
                .injectClosed(
                        // A single continuous ramp with no plateau: the run exists to find where
                        // the curve bends, and holding at any level would only spend time at a
                        // load already known to be survivable.
                        rampConcurrentUsers(1).to(maxUsers).during(duration)))
                .protocols(BookCatalog.httpProtocol())
                // Stop climbing once the server is clearly gone: everything past that point is
                // failed requests that cost money and tell us nothing we did not already know.
                .maxDuration(duration.plusMinutes(1));
    }

    @Override
    public void before() {
        BookCatalog.warmUp();
    }
}
