package com.example.bookserver.loadtest;

import java.time.Duration;

/**
 * Every knob a simulation reads, in one place.
 *
 * <p>Values come from system properties so the same compiled simulation can be pointed at a
 * different environment or re-shaped without editing code — the profiles below are defaults,
 * not commitments. Pass them through Gradle:
 *
 * <pre>./gradlew gatlingRun --simulation=…LoadSimulation -PbaseUrl=http://localhost:8080 -Pusers=20</pre>
 *
 * <p>The build forwards {@code -P} properties to the Gatling JVM as {@code -D} (see build.gradle).
 */
public final class LoadTestConfig {

    private LoadTestConfig() {
    }

    /**
     * The deployed Cloud Run revision. Load figures measured anywhere else are not this
     * server's numbers: run locally and the load generator, the app and PostgreSQL all
     * contend for the same CPU, so the bottleneck cannot be attributed. Local is for
     * proving the script runs, not for measurement.
     */
    public static final String BASE_URL =
            System.getProperty("baseUrl", "https://book-server-912285810536.asia-northeast3.run.app");

    /**
     * Pause between a virtual user's requests, drawn uniformly at random from
     * [{@code thinkTimeMin}, {@code thinkTimeMax}]. Real users read the page before clicking
     * the next thing, and without that pause "100 users" means 100 clients hammering as fast
     * as the network allows — a throughput measurement wearing a concurrency label.
     *
     * <p>Randomised rather than fixed because a constant pause keeps users in lockstep: they
     * arrive together, wait the same interval, and arrive together again, so the server sees
     * a pulse that no real population produces. Spreading the interval decorrelates them
     * after the first cycle.
     *
     * <p>1–5 seconds is a plausible range for skimming a book page. Set both to 0
     * ({@code -PthinkTime=0}) to measure the throughput ceiling instead of a realistic user
     * count — the two are different questions with very different answers.
     */
    public static final Duration THINK_TIME_MIN = thinkTimeMin(1);

    public static final Duration THINK_TIME_MAX = thinkTimeMax(5);

    /**
     * Think-time bounds with a caller-supplied default, for simulations whose natural pause is
     * not the browsing default — BreakPoint wants none at all, since its job is to reach the
     * throughput ceiling rather than to imitate a reader. An explicit {@code -PthinkTime…} on
     * the command line still wins over whatever the simulation asks for.
     */
    public static Duration thinkTimeMin(long fallbackSeconds) {
        return seconds("thinkTimeMin", fixedThinkTime(fallbackSeconds));
    }

    public static Duration thinkTimeMax(long fallbackSeconds) {
        return seconds("thinkTimeMax", fixedThinkTime(fallbackSeconds));
    }

    /**
     * {@code -PthinkTime=N} pins both ends of the range to N, which is how a fixed pause (or
     * no pause at all) is requested without having to set the two bounds separately.
     */
    private static long fixedThinkTime(long fallback) {
        String raw = System.getProperty("thinkTime");
        return raw == null || raw.isBlank() ? fallback : Long.parseLong(raw.trim());
    }

    /**
     * Requests fired before measurement starts, to wake a scaled-to-zero revision. Cloud Run
     * drops to zero instances when idle, so the first request pays a JVM cold start of several
     * seconds. Left in the sample it lands in the p99 and reads as a latency problem that does
     * not exist under sustained load. Measuring cold start is a separate test, not a side
     * effect of this one.
     */
    public static final int WARMUP_REQUESTS = intProp("warmupRequests", 5);

    /**
     * Whether all virtual users share one connection pool. On by default because the alternative
     * exhausts Windows' ephemeral port range long before the server is troubled — see
     * {@link BookCatalog#httpProtocol()} for what that trade-off costs.
     */
    public static final boolean SHARE_CONNECTIONS =
            !"false".equalsIgnoreCase(System.getProperty("shareConnections", "true"));

    /**
     * Default catalogue-browsing mix. These are placeholders until production access logs can
     * supply observed ratios: 50% search by title and the remaining 50% open a book detail page.
     *
     * <p>The endpoint-specific simulations bypass these values entirely. They only shape the
     * mixed Load/Stress/Spike/Endurance and catalogue breakpoint profiles.
     */
    public static final double TITLE_SEARCH_PCT = percentage("titleSearchPct", 50);

    public static final double BOOK_DETAIL_PCT = detailPercentage();

    public static double doubleProp(String key, double fallback) {
        String raw = System.getProperty(key);
        return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim());
    }

    private static double percentage(String key, double fallback) {
        double value = doubleProp(key, fallback);
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(key + " must be between 0 and 100, got " + value);
        }
        return value;
    }

    private static double detailPercentage() {
        return 100 - TITLE_SEARCH_PCT;
    }

    public static int intProp(String key, int fallback) {
        String raw = System.getProperty(key);
        return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
    }

    /** Reads {@code key} as a number of seconds, falling back to {@code fallbackSeconds}. */
    public static Duration seconds(String key, long fallbackSeconds) {
        String raw = System.getProperty(key);
        return Duration.ofSeconds(raw == null || raw.isBlank() ? fallbackSeconds : Long.parseLong(raw.trim()));
    }
}
