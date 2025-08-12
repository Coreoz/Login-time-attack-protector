package com.coreoz.securelogin;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Unit tests for the TimingAttackProtector class.
 */
class TimingAttackProtectorTest {

    // Helper method to create a supplier that simulates work by sleeping.
    private Supplier<Boolean> workSimulator(long millis) {
        return () -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        };
    }

    // Helper method to access the private durationSamples field for verification.
    @SuppressWarnings("unchecked")
    private Collection<Long> getInternalSamples(TimingAttackProtector protector) throws NoSuchFieldException, IllegalAccessException {
        Field field = TimingAttackProtector.class.getDeclaredField("durationSamples");
        field.setAccessible(true);
        return (Collection<Long>) field.get(protector);
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("constructor: with valid config => creates instance successfully")
        void constructor_withValidConfig_createsInstance() {
            // when & then
            Assertions.assertThatCode(() -> new TimingAttackProtector(new TimingAttackProtector.Config(100, 0.5)))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("constructor: with zero maxSamples => throws IllegalArgumentException")
        void constructor_withZeroMaxSamples_throwsException() {
            // when & then
            Assertions.assertThatThrownBy(() -> new TimingAttackProtector(new TimingAttackProtector.Config(0, 0.5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxSamples must be positive.");
        }

        @Test
        @DisplayName("constructor: with negative samplingRate => throws IllegalArgumentException")
        void constructor_withNegativeSamplingRate_throwsException() {
            // when & then
            Assertions.assertThatThrownBy(() -> new TimingAttackProtector(new TimingAttackProtector.Config(100, -0.1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("samplingRate must be between 0.0 and 1.0.");
        }

        @Test
        @DisplayName("constructor: with samplingRate over 1.0 => throws IllegalArgumentException")
        void constructor_withSamplingRateOverOne_throwsException() {
            // when & then
            Assertions.assertThatThrownBy(() -> new TimingAttackProtector(new TimingAttackProtector.Config(100, 1.1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("samplingRate must be between 0.0 and 1.0.");
        }
    }

    @Nested
    @DisplayName("measureAndExecute Method")
    class MeasureAndExecuteTests {

        @Test
        @DisplayName("measureAndExecute: when function returns value => returns same value")
        void measureAndExecute_whenFunctionReturnsValue_returnsSameValue() {
            // given
            TimingAttackProtector protector = new TimingAttackProtector(new TimingAttackProtector.Config(10, 1.0));
            String expectedResult = "SUCCESS";
            @SuppressWarnings("unchecked")
            Supplier<String> mockSupplier = Mockito.mock(Supplier.class);
            Mockito.when(mockSupplier.get()).thenReturn(expectedResult);

            // when
            String actualResult = protector.measureAndExecute(mockSupplier);

            // then
            Assertions.assertThat(actualResult).isEqualTo(expectedResult);
            Mockito.verify(mockSupplier).get();
        }

        @Test
        @DisplayName("measureAndExecute: when function throws exception => propagates exception and still records time")
        void measureAndExecute_whenFunctionThrows_propagatesAndRecords() throws Exception {
            // given
            TimingAttackProtector protector = new TimingAttackProtector(new TimingAttackProtector.Config(10, 1.0));
            RuntimeException exception = new RuntimeException("Authentication Failed");
            @SuppressWarnings("unchecked")
            Supplier<String> mockSupplier = Mockito.mock(Supplier.class);
            Mockito.when(mockSupplier.get()).thenThrow(exception);

            // when & then
            Assertions.assertThatThrownBy(() -> protector.measureAndExecute(mockSupplier))
                .isSameAs(exception);

            // and then verify a sample was still recorded
            Assertions.assertThat(getInternalSamples(protector)).hasSize(1);
            Assertions.assertThat(protector.generateDelayNanos()).isGreaterThan(0);
        }

        @Test
        @DisplayName("measureAndExecute: when below max samples => adds a new sample each time")
        void measureAndExecute_whenBelowMax_addsSample() throws Exception {
            // given
            TimingAttackProtector protector = new TimingAttackProtector(new TimingAttackProtector.Config(5, 1.0));

            // when
            protector.measureAndExecute(workSimulator(10));
            protector.measureAndExecute(workSimulator(10));
            protector.measureAndExecute(workSimulator(10));

            // then
            Assertions.assertThat(getInternalSamples(protector)).hasSize(3);
        }

        @Test
        @DisplayName("measureAndExecute: when at max samples with 100% rate => replaces oldest sample")
        void measureAndExecute_whenAtMaxWithFullRate_replacesOldest() throws Exception {
            // given
            TimingAttackProtector protector = new TimingAttackProtector(new TimingAttackProtector.Config(3, 1.0));

            // Use durations that are far apart to avoid ambiguity from sleep inaccuracy.
            long firstSleep = 10;
            long secondSleep = 30;
            long thirdSleep = 50;
            long fourthSleep = 70;

            long firstDurationNanos = Duration.ofMillis(firstSleep).toNanos();
            long secondDurationNanos = Duration.ofMillis(secondSleep).toNanos();

            // A generous tolerance to account for thread scheduling delays in test environments.
            // The goal is to check the queue logic, not the precision of Thread.sleep.
            long toleranceNanos = Duration.ofMillis(25).toNanos();

            // when
            protector.measureAndExecute(workSimulator(firstSleep));  // ~10ms
            protector.measureAndExecute(workSimulator(secondSleep)); // ~30ms
            protector.measureAndExecute(workSimulator(thirdSleep));  // ~50ms

            // then
            Collection<Long> samplesBefore = getInternalSamples(protector);
            Assertions.assertThat(samplesBefore).hasSize(3);
            // Assert that the first sample is indeed the first one we added.
            Assertions.assertThat(samplesBefore.iterator().next())
                .isCloseTo(firstDurationNanos, Assertions.within(toleranceNanos));

            // when
            protector.measureAndExecute(workSimulator(fourthSleep)); // ~70ms, should push out the ~10ms sample

            // then
            Collection<Long> samplesAfter = getInternalSamples(protector);
            Assertions.assertThat(samplesAfter).hasSize(3);
            // Assert that the new head of the queue is the second sample we added.
            // This is the crucial check for the FIFO replacement logic.
            Assertions.assertThat(samplesAfter.iterator().next())
                .isCloseTo(secondDurationNanos, Assertions.within(toleranceNanos));
        }

        @Test
        @DisplayName("measureAndExecute: when at max samples with 0% rate => does not replace sample")
        void measureAndExecute_whenAtMaxWithZeroRate_doesNotReplace() throws Exception {
            // given
            TimingAttackProtector protector = new TimingAttackProtector(new TimingAttackProtector.Config(3, 0.0));
            protector.measureAndExecute(workSimulator(10));
            protector.measureAndExecute(workSimulator(20));
            protector.measureAndExecute(workSimulator(30));

            // Create a copy of the state before the next execution
            List<Long> samplesBefore = List.copyOf(getInternalSamples(protector));

            // when
            protector.measureAndExecute(workSimulator(99)); // This execution should be ignored

            // then
            List<Long> samplesAfter = List.copyOf(getInternalSamples(protector));
            Assertions.assertThat(samplesAfter).containsExactlyElementsOf(samplesBefore);
        }
    }

    @Nested
    @DisplayName("generateDelayNanos Method")
    class GenerateDelayNanosTests {

        @Test
        @DisplayName("generateDelayNanos: when no samples exist => returns 0")
        void generateDelayNanos_whenNoSamples_returnsZero() {
            // given
            TimingAttackProtector protector = new TimingAttackProtector(new TimingAttackProtector.Config(10, 1.0));

            // when
            long delay = protector.generateDelayNanos();

            // then
            Assertions.assertThat(delay).isZero();
        }

        @Test
        @DisplayName("generateDelayNanos: with constant samples => returns delay equal to the mean")
        void generateDelayNanos_withConstantSamples_returnsMean() {
            // given
            TimingAttackProtector protector = new TimingAttackProtector(new TimingAttackProtector.Config(10, 1.0));
            long constantDurationMillis = 25;
            long constantDurationNanos = Duration.ofMillis(constantDurationMillis).toNanos();

            // when
            // Simulate 5 executions with the same duration
            for (int i = 0; i < 5; i++) {
                protector.measureAndExecute(workSimulator(constantDurationMillis));
            }
            long delay = protector.generateDelayNanos();

            // then
            // With zero standard deviation, the delay should be exactly the mean.
            // We allow a generous tolerance for the overhead of Thread.sleep().
            Assertions.assertThat(delay).isCloseTo(constantDurationNanos, Assertions.within(Duration.ofMillis(25).toNanos()));
        }

        @Test
        @DisplayName("generateDelayNanos: with any samples => never returns a negative delay")
        void generateDelayNanos_withAnySamples_isAlwaysNonNegative() {
            // given
            // A tiny mean and a huge standard deviation makes a negative result from the raw
            // (mean + stdDev * gaussian) formula very likely. This tests the Math.max(0, ...) clamp.
            TimingAttackProtector protector = new TimingAttackProtector(new TimingAttackProtector.Config(2, 1.0));
            protector.measureAndExecute(workSimulator(1));   // ~1ms
            protector.measureAndExecute(workSimulator(100)); // ~100ms

            // when & then
            // Generate many delays and assert none are negative.
            for (int i = 0; i < 1000; i++) {
                Assertions.assertThat(protector.generateDelayNanos()).isNotNegative();
            }
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("measureAndExecute: when called by multiple threads => maintains data integrity")
        void measureAndExecute_whenConcurrent_isThreadSafe() throws Exception {
            // given
            int threadCount = 10;
            int executionsPerThread = 10;
            int maxSamples = 50;
            int totalExecutions = threadCount * executionsPerThread;

            TimingAttackProtector protector = new TimingAttackProtector(new TimingAttackProtector.Config(maxSamples, 1.0));
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(totalExecutions);

            // when
            for (int i = 0; i < totalExecutions; i++) {
                executor.submit(() -> {
                    protector.measureAndExecute(workSimulator(1));
                    latch.countDown();
                });
            }

            latch.await(); // Wait for all tasks to complete
            executor.shutdown();

            // then
            // The number of samples should be exactly maxSamples, as totalExecutions > maxSamples
            Assertions.assertThat(getInternalSamples(protector)).hasSize(maxSamples);
        }
    }
}
