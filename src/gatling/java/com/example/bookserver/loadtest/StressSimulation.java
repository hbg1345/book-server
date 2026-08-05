package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;

import io.gatling.javaapi.core.Simulation;

/**
 * <strong>Stress test</strong> — well past the expected load, then back down again.
 *
 * <p>Three phases, and the third is the point:
 * <ol>
 *   <li>ramp to a multiple of normal load,</li>
 *   <li>hold it there — does the server fail, and if so, does it fail cleanly (503, refused
 *       connections) rather than hanging, corrupting data, or dying outright?</li>
 *   <li><strong>drop back to normal and stay there</strong> — does it recover on its own?</li>
 * </ol>
 *
 * <p>Skipping the recovery phase is the usual mistake. A server that survives overload but
 * never returns to healthy latency afterwards — pool exhausted, queues still draining, GC
 * thrashing — has failed a test that a run ending at peak would have called a pass.
 *
 * <p>Defaults to 4× the {@link LoadSimulation} figure, so set {@code -Pusers} from a real
 * BreakPoint result rather than trusting the number here.
 *
 * <p><strong>No assertions.</strong> Errors under overload are the expected observation, not a
 * defect — asserting a success rate would fail every run by design. Read the report instead:
 * how far latency climbed at peak, and whether it came back down in phase three.
 */
public class StressSimulation extends Simulation {

    private final int users = LoadTestConfig.intProp("users", 200);
    private final int baseline = LoadTestConfig.intProp("baselineUsers", 50);
    private final java.time.Duration rampUp = LoadTestConfig.seconds("rampUp", 120);
    private final java.time.Duration duration = LoadTestConfig.seconds("duration", 600);
    private final java.time.Duration recovery = LoadTestConfig.seconds("recovery", 300);

    {
        setUp(BookCatalog.readScenario("stress").injectClosed(
                        rampConcurrentUsers(0).to(users).during(rampUp),
                        constantConcurrentUsers(users).during(duration),
                        // Drop sharply rather than ramping down: recovery from a cliff is the
                        // realistic case, and a gentle ramp would let the server heal on the way.
                        rampConcurrentUsers(users).to(baseline).during(java.time.Duration.ofSeconds(30)),
                        constantConcurrentUsers(baseline).during(recovery)))
                .protocols(BookCatalog.httpProtocol());
    }

    @Override
    public void before() {
        BookCatalog.warmUp();
    }
}
