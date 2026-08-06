package com.example.bookserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks genuinely at the same time.
 *
 * <p>Submitting to a pool only makes tasks <em>eligible</em> to run: threads that start
 * milliseconds apart serialise on their own, and a concurrency test then quietly stops testing
 * concurrency while still passing. The latch holds every worker at the gate until all of them
 * are parked on it, so the race being investigated actually happens.
 */
public final class Concurrently {

    private Concurrently() {
    }

    /** Run every task at once and return their results, in submission order. */
    public static <T> List<T> runAtOnce(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The same call made {@code times} times at once — the double-click shape. */
    public static <T> List<T> runAtOnce(int times, Callable<T> task) throws Exception {
        List<Callable<T>> copies = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            copies.add(task);
        }
        return runAtOnce(copies);
    }

    /**
     * Swallow an exception a losing racer is entitled to throw, so the task can be handed to
     * {@link #runAtOnce} without each caller writing its own try/catch.
     */
    public static Callable<Void> ignoring(Class<? extends RuntimeException> expected, Runnable action) {
        return () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                if (!expected.isInstance(e)) {
                    throw e;
                }
            }
            return null;
        };
    }
}
