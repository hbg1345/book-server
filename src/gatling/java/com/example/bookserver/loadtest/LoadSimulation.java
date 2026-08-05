package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;

import io.gatling.javaapi.core.Simulation;

/**
 * <strong>Load test</strong> — a fixed load held for a fixed time.
 *
 * <p>This is the baseline every other profile is expressed relative to: it answers "under the
 * traffic we expect, what does a user actually experience?" Nothing here is meant to break;
 * a run that degrades is reporting a problem, not doing its job.
 *
 * <p>Conventionally an hour. The default here is 10 minutes because a shorter run still shows
 * steady-state latency, and an hour of Cloud Run and Cloud SQL costs real money on a personal
 * project. Pass {@code -Pduration=3600} for the textbook version.
 *
 * <p><strong>The 50-user default is a placeholder, not a finding.</strong> Until
 * {@link BreakPointSimulation} has reported where this server actually saturates, any number
 * here is invented. Run BreakPoint first, then set this to roughly half of what it survived.
 */
public class LoadSimulation extends Simulation {

    private final int users = LoadTestConfig.intProp("users", 50);
    private final java.time.Duration rampUp = LoadTestConfig.seconds("rampUp", 60);
    private final java.time.Duration duration = LoadTestConfig.seconds("duration", 600);

    {
        setUp(BookCatalog.readScenario("load").injectClosed(
                        // Ramp rather than drop all users at once: an instant arrival of the full
                        // population measures Cloud Run's scale-out, which is a different question.
                        rampConcurrentUsers(0).to(users).during(rampUp),
                        constantConcurrentUsers(users).during(duration)))
                .protocols(BookCatalog.httpProtocol())
                // Assertions belong on this profile precisely because it is the one expected to
                // pass. They make the run a pass/fail check rather than a wall of numbers, and
                // give CI something to gate on.
                .assertions(
                        global().successfulRequests().percent().gt(99.0),
                        global().responseTime().percentile3().lt(1000));
    }

    @Override
    public void before() {
        BookCatalog.warmUp();
    }
}
