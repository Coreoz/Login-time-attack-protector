package com.coreoz.securelogin;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * A utility class designed to mitigate timing attacks in authentication systems.
 * <p>
 * This class works by measuring the execution time of legitimate authentication
 * operations. It maintains a statistical profile (mean and standard deviation)
 * of these execution times. When an authentication attempt fails because a user
 * does not exist, a random delay can be generated using {@link #generateDelayNanos()}
 * that mimics the timing of a real authentication check. This makes it significantly
 * harder for an attacker to distinguish between a non-existent user and a valid
 * user with an incorrect password based on response time.
 * <p>
 * This class is thread-safe and optimized for high-concurrency environments.
 */
public final class TimingAttackProtector {
    /**
     * Configuration for the TimingAttackProtector.
     *
     * @param maxSamples   The maximum number of execution time samples to keep for
     * statistical analysis. A larger number provides more
     * statistical accuracy but consumes more memory.
     * The default value is 10: it enables to reach the first JVM improvements and
     * to provide values that are representative enough
     * @param samplingRate The probability (from 0.0 to 1.0) of recording a new
     * execution time once {@code maxSamples} has been reached.
     * For example, 0.2 means there is a 20% chance a new
     * measurement will replace the oldest one. This prevents the
     * statistical model from becoming stale without incurring the
     * cost of updating on every single execution.
     * The default value is 0.22: we are using here a non-round number to
     * make it harding to see a pattern (like every 5 executions, the time to authenticate increases a bit).
     */
    public record Config(int maxSamples, double samplingRate) {
        /**
         * Validates the configuration parameters.
         */
        public Config {
            if (maxSamples <= 0) {
                throw new IllegalArgumentException("maxSamples must be positive.");
            }
            if (samplingRate < 0.0 || samplingRate > 1.0) {
                throw new IllegalArgumentException("samplingRate must be between 0.0 and 1.0.");
            }
        }

        /**
         * Creates a new configuration with default parameters values
         */
        public Config() {
            this(10, 0.22);
        }
    }

    private final Config config;
    private final Queue<Long> durationSamples;

    // Volatile ensures that reads and writes are atomic and visible across threads.
    // This is crucial for the cached statistics, which are read in the non-synchronized
    // generateDelayNanos() method.
    private volatile double cachedMean = 0.0;
    private volatile double cachedStandardDeviation = 0.0;

    /**
     * Constructs a new TimingAttackProtector with the specified configuration.
     *
     * @param config The configuration object.
     */
    public TimingAttackProtector(Config config) {
        this.config = config;
        // ArrayDeque is a highly efficient implementation of a queue.
        this.durationSamples = new ArrayDeque<>(config.maxSamples());
    }

    /**
     * Returns a new instance of TimingAttackProtector with default configuration.
     * See {@link Config#Config()} for default parameters values.
     */
    public static TimingAttackProtector fromDefaultConfig() {
        return new TimingAttackProtector(new Config());
    }

    /**
     * Executes the supplied function, measures its execution duration, and records
     * it for statistical analysis according to the configured sampling policy.
     *
     * @param function The function to execute (e.g., a password checking lambda).
     * @param <T>      The return type of the function.
     * @return The result returned by the supplied function.
     */
    public <T> T measureAndExecute(Supplier<T> function) {
        // System.nanoTime() is used for its precision and because it's a monotonic
        // clock, unaffected by system time changes (e.g., NTP updates).
        final long startTimeNanos = System.nanoTime();
        try {
            return function.get();
        } finally {
            final long durationNanos = System.nanoTime() - startTimeNanos;
            addSample(durationNanos);
        }
    }

    /**
     * Generates a random delay in nanoseconds that follows the statistical
     * distribution (mean, standard deviation) of the recorded execution times.
     * <p>
     * This method is highly optimized for frequent calls. It reads from cached,
     * volatile statistics and does not require locking.
     * <p>
     * The generated delay is clamped to be non-negative.
     *
     * @return A randomized delay in nanoseconds. Returns 0 if no samples have been
     * recorded yet.
     */
    public long generateDelayNanos() {
        // Reading volatile fields into local variables ensures we work with a
        // consistent pair of values throughout the calculation.
        final double mean = this.cachedMean;
        final double stdDev = this.cachedStandardDeviation;

        if (mean == 0.0) {
            // If there's no data yet, we cannot generate a meaningful delay.
            return 0L;
        }

        // ThreadLocalRandom is the preferred choice in concurrent applications.
        // nextGaussian() returns a value from a standard normal distribution (mean=0, stddev=1).
        // We scale and shift it to match our measured distribution.
        final double gaussian = ThreadLocalRandom.current().nextGaussian();
        final long delay = (long) (gaussian * stdDev + mean);

        // A Gaussian distribution can produce negative values, but a delay cannot be negative.
        // We therefore clamp the result at 0.
        return Math.max(0L, delay);
    }

    /**
     * A convenience method to generate a delay as a {@link Duration} object.
     *
     * @return A randomized delay as a Duration object.
     */
    public Duration generateDelay() {
        return Duration.ofNanos(generateDelayNanos());
    }

    /**
     * Adds a new duration sample to the collection and triggers a recalculation
     * of the statistics if the sample set is modified.
     * <p>
     * This method is synchronized to ensure thread-safe modification of the
     * durationSamples queue and the cached statistical values.
     *
     * @param durationNanos The execution duration in nanoseconds.
     */
    private synchronized void addSample(long durationNanos) {
        boolean datasetChanged = false;

        if (durationSamples.size() < config.maxSamples()) {
            durationSamples.offer(durationNanos);
            datasetChanged = true;
        } else if (shouldSample()) {
            // When full, remove the oldest sample and add the new one to keep the
            // sample set current.
            durationSamples.poll(); // Removes head
            durationSamples.offer(durationNanos); // Adds to tail
            datasetChanged = true;
        }

        if (datasetChanged) {
            recalculateStatistics();
        }
    }

    /**
     * Determines if a new sample should be taken based on the configured sampling rate.
     *
     * @return true if sampling should occur, false otherwise.
     */
    private boolean shouldSample() {
        // Avoids the check if rate is 1.0, which is a common configuration.
        if (config.samplingRate() == 1.0) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < config.samplingRate();
    }

    /**
     * Recalculates the mean and standard deviation for the current set of samples.
     * This is a private method that should only be called from within a
     * synchronized context to prevent race conditions.
     */
    private void recalculateStatistics() {
        if (durationSamples.isEmpty()) {
            this.cachedMean = 0.0;
            this.cachedStandardDeviation = 0.0;
            return;
        }

        // Using streams for a clean and expressive calculation.
        // First, calculate the mean.
        final double mean = durationSamples.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0.0);

        // Second, calculate the standard deviation based on the new mean.
        final double variance = durationSamples.stream()
            .mapToLong(Long::longValue)
            .mapToDouble(duration -> Math.pow(duration - mean, 2))
            .average()
            .orElse(0.0);

        // Update the volatile fields. This write will be visible to all other threads.
        this.cachedMean = mean;
        this.cachedStandardDeviation = Math.sqrt(variance);
    }
}
