package com.example.bookserver.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;

import io.gatling.javaapi.core.Simulation;
import java.time.Duration;

/**
 * <strong>Spike test</strong> — an instantaneous surge far beyond what the server can absorb.
 *
 * <p>Distinct from {@link StressSimulation} in shape, not just size. Stress ramps up and holds,
 * giving Cloud Run time to scale out; a spike gives it none. This is the traffic pattern of a
 * link going viral or a marketing email landing: the question is whether the server survives
 * the seconds before autoscaling catches up, and how quickly it settles once it has.
 *
 * <p>Two populations run against the same server. A baseline of ordinary users runs throughout,
 * and the spike is layered on top partway in. Keeping the baseline separate is what makes the
 * report readable: it shows what a normal user experienced <em>while</em> the surge was
 * happening, which is the thing anyone actually cares about.
 *
 * <p><strong>What to look for.</strong> Not the peak error rate — errors are expected. Look at
 * how long the baseline population stayed degraded <em>after</em> the spike ended. That gap is
 * the recovery time, and it is the number this test exists to produce.
 *
 * <p>No assertions, for the same reason as the stress profile: failure is the observation.
 */
public class SpikeSimulation extends Simulation {

    private final int baseline = LoadTestConfig.intProp("baselineUsers", 20);
    private final int spike = LoadTestConfig.intProp("spikeUsers", 400);
    private final Duration beforeSpike = LoadTestConfig.seconds("beforeSpike", 120);
    private final Duration spikeHold = LoadTestConfig.seconds("spikeHold", 60);
    private final Duration afterSpike = LoadTestConfig.seconds("afterSpike", 300);

    private final Duration total = beforeSpike.plus(spikeHold).plus(afterSpike);

    {
        setUp(
                // Ordinary traffic, running for the whole test — the victim, and the measurement.
                BookCatalog.readScenario("baseline").injectClosed(
                        constantConcurrentUsers(baseline).during(total)),

                // The surge. Ten seconds to full is as close to instantaneous as is useful:
                // truly zero would only measure Gatling's own thread start-up.
                BookCatalog.readScenario("spike").injectClosed(
                        // Closed-model equivalent of nothingFor(): hold zero users. The open-model
                        // step cannot be mixed into a closed injection profile.
                        constantConcurrentUsers(0).during(beforeSpike),
                        rampConcurrentUsers(0).to(spike).during(Duration.ofSeconds(10)),
                        constantConcurrentUsers(spike).during(spikeHold),
                        // Vanish as abruptly as they arrived, leaving the baseline to reveal how
                        // long the server takes to come back.
                        rampConcurrentUsers(spike).to(0).during(Duration.ofSeconds(10))))
                .protocols(BookCatalog.httpProtocol());
    }

    @Override
    public void before() {
        BookCatalog.warmUp();
    }
}
