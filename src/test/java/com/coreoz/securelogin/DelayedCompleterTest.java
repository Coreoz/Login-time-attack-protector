package com.coreoz.securelogin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/**
 * Unit tests for the {@link DelayedCompleter} class.
 * These tests verify the core functionality, shutdown behavior, and concurrency safety,
 * organized by the method under test.
 */
@DisplayName("DelayedCompleter Tests")
class DelayedCompleterTest {

    private DelayedCompleter delayedCompleter;

    @BeforeEach
    void setUp() {
        // Create a new instance for each test to ensure isolation.
        delayedCompleter = new DelayedCompleter();
    }

    @AfterEach
    void tearDown() {
        // Ensure the completer is closed after each test.
        if (delayedCompleter != null) {
            delayedCompleter.close();
        }
    }

    @Nested
    @DisplayName("waitDuration()")
    class WaitDurationTests {

        @Test
        @Timeout(2)
        @DisplayName("when duration is positive => should complete future after the specified delay")
        void waitDuration__whenDurationIsPositive__shouldCompleteFutureAfterDelay() throws Exception {
            // Arrange
            final Duration delay = Duration.ofMillis(100);
            long startTime = System.currentTimeMillis();

            // Act
            CompletableFuture<Void> future = delayedCompleter.waitDuration(delay);

            // Assert
            future.get(); // Block until completion
            long endTime = System.currentTimeMillis();
            long elapsed = endTime - startTime;

            assertThat(elapsed).isGreaterThanOrEqualTo(delay.toMillis());
        }

        @Test
        @Timeout(2)
        @DisplayName("when futures are scheduled out of order => should complete them in order of expiry")
        void waitDuration__whenFuturesAreScheduledOutOfOrder__shouldCompleteThemInOrderOfExpiry() throws Exception {
            // Arrange
            final List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
            final CountDownLatch latch = new CountDownLatch(2);

            // Act
            delayedCompleter.waitDuration(Duration.ofMillis(500)).thenRun(() -> {
                completionOrder.add("long");
                latch.countDown();
            });
            delayedCompleter.waitDuration(Duration.ofMillis(100)).thenRun(() -> {
                completionOrder.add("short");
                latch.countDown();
            });

            // Assert
            boolean completedInTime = latch.await(1, TimeUnit.SECONDS);
            assertThat(completedInTime).isTrue();
            assertThat(completionOrder).containsExactly("short", "long");
        }

        @Test
        @Timeout(1)
        @DisplayName("when duration is zero => should complete future almost immediately")
        void waitDuration__whenDurationIsZero__shouldCompleteFutureAlmostImmediately() throws Exception {
            // Arrange
            final Duration zeroDelay = Duration.ZERO;

            // Act
            CompletableFuture<Void> future = delayedCompleter.waitDuration(zeroDelay);

            // Assert
            future.get(100, TimeUnit.MILLISECONDS);
            assertThat(future).isDone();
        }

        @Test
        @DisplayName("when called after close() => should throw IllegalStateException")
        void waitDuration__whenCalledAfterClose__shouldThrowIllegalStateException() {
            // Arrange
            delayedCompleter.close();

            // Act & Assert
            assertThatThrownBy(() -> delayedCompleter.waitDuration(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DelayedCompleter has been shut down.");
        }

        @Test
        @Timeout(2)
        @DisplayName("when called from multiple threads concurrently => should all complete successfully")
        void waitDuration__whenCalledFromMultipleThreads__shouldAllCompleteSuccessfully() throws Exception {
            // Arrange
            final int threadCount = 50;
            final CountDownLatch startLatch = new CountDownLatch(1);
            final CountDownLatch finishLatch = new CountDownLatch(threadCount);
            final List<CompletableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());

            // Act
            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        futures.add(delayedCompleter.waitDuration(Duration.ofMillis(100)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            boolean allThreadsFinishedSubmission = finishLatch.await(1, TimeUnit.SECONDS);

            // Assert
            assertThat(allThreadsFinishedSubmission).as("All threads should finish submitting their future").isTrue();
            assertThat(futures).hasSize(threadCount);

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(1, TimeUnit.SECONDS);
            assertThat(futures).allMatch(CompletableFuture::isDone);
        }
    }

    @Nested
    @DisplayName("close()")
    class CloseTests {

        @Test
        @Timeout(1)
        @DisplayName("when futures are pending => should complete them exceptionally")
        void close__whenFuturesArePending__shouldCompleteThemExceptionally() {
            // Arrange
            CompletableFuture<Void> pendingFuture = delayedCompleter.waitDuration(Duration.ofSeconds(5));

            // Act
            delayedCompleter.close();

            // Assert
            assertThat(pendingFuture).isCompletedExceptionally();
            assertThatThrownBy(pendingFuture::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DelayedCompleter was shut down.");
        }

        @Test
        @Timeout(1)
        @DisplayName("when called => should terminate the background worker thread")
        void close__whenCalled__shouldTerminateWorkerThread() throws Exception {
            // Arrange
            Field workerThreadField = DelayedCompleter.class.getDeclaredField("workerThread");
            workerThreadField.setAccessible(true);
            Thread workerThread = (Thread) workerThreadField.get(delayedCompleter);
            assertThat(workerThread.isAlive()).isTrue();

            // Act
            delayedCompleter.close();

            // Assert
            workerThread.join(Duration.ofMillis(100).toMillis());
            assertThat(workerThread.isAlive()).isFalse();
        }
    }
}

