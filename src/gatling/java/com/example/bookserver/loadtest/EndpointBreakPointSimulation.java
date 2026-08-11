package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.time.Duration;

/** Shared ramp shape for endpoint-only capacity tests. */
abstract class EndpointBreakPointSimulation extends Simulation {

    EndpointBreakPointSimulation(ScenarioBuilder scenario) {
        int maxUsers = LoadTestConfig.intProp("maxUsers", 500);
        Duration duration = LoadTestConfig.seconds("duration", 120);

        setUp(scenario.injectClosed(
                        rampConcurrentUsers(1).to(maxUsers).during(duration)))
                .protocols(BookCatalog.httpProtocol())
                .maxDuration(duration.plusMinutes(1));
    }

    @Override
    public void before() {
        BookCatalog.warmUp();
    }
}
